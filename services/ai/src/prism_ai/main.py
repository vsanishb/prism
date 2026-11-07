from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
import structlog
from fastapi import FastAPI

from prism_ai.api.documents import processing_failure
from prism_ai.api.documents import router as documents_router
from prism_ai.api.internal import router as internal_router
from prism_ai.api.service_auth import ServiceTokenVerifier
from prism_ai.database import create_pool
from prism_ai.documents.pipeline import DocumentPipeline
from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.errors import ProcessingError
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import load_routes
from prism_ai.policies.pipeline import PolicyPipeline
from prism_ai.settings import Settings, get_settings

structlog.configure(
    processors=[structlog.processors.TimeStamper(fmt="iso"), structlog.processors.JSONRenderer()]
)


def model_http_client(config: Settings) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        # The base URL needs its trailing slash for relative paths to keep the /api/v1 prefix.
        base_url=str(config.openrouter_base_url).rstrip("/") + "/",
        headers={"Authorization": f"Bearer {config.openrouter_api_key.get_secret_value()}"},
        timeout=config.model_request_timeout_seconds,
    )


def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # Open lazily: a brief database outage must not stop the service from starting.
        pool = create_pool(config.database_url.get_secret_value())
        await pool.open(wait=False)
        app.state.db_pool = pool
        app.state.token_verifier = ServiceTokenVerifier(
            config.service_jwt_public_key_path.read_text(encoding="ascii"),
            config.service_token_max_lifetime_seconds,
        )
        async with model_http_client(config) as http:
            gateway = ModelGateway(http, load_routes(), RetentionCatalog(http))
            app.state.document_pipeline = DocumentPipeline(gateway)
            app.state.policy_pipeline = PolicyPipeline(gateway)
            yield
        await pool.close()

    # Interactive docs are disabled: this API is for the core service only.
    app = FastAPI(title="Prism AI", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.include_router(internal_router)
    app.include_router(documents_router)
    app.add_exception_handler(ProcessingError, processing_failure)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "ok"}

    return app
