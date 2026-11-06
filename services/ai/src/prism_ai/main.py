from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from prism_ai.api.internal import router as internal_router
from prism_ai.api.service_auth import ServiceTokenVerifier
from prism_ai.database import create_pool
from prism_ai.settings import Settings, get_settings

structlog.configure(
    processors=[structlog.processors.TimeStamper(fmt="iso"), structlog.processors.JSONRenderer()]
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
        yield
        await pool.close()

    # Interactive docs are disabled: this API is for the core service only.
    app = FastAPI(title="Prism AI", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.include_router(internal_router)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "ok"}

    return app
