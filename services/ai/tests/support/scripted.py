"""A scripted OpenRouter, so that gateway and pipeline tests run offline and deterministically."""

import json
from collections.abc import Callable
from typing import Any

import httpx

from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import RouteTable, load_routes

BASE_URL = "https://openrouter.test/api/v1/"

Reply = httpx.Response | Exception


def completion(content: Any, finish_reason: str = "stop", provider: str = "Google") -> httpx.Response:
    text = content if isinstance(content, str) else json.dumps(content)
    return httpx.Response(
        200,
        json={
            "provider": provider,
            "choices": [{"finish_reason": finish_reason, "message": {"role": "assistant", "content": text}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "cost": 0.0012},
        },
    )


def catalog_entries(routes: RouteTable) -> list[dict[str, Any]]:
    models = {model for route in routes.tasks.values() for model in route.models()}
    return [{"model_id": model, "safety_retention": False} for model in models]


class ScriptedProvider:
    """Replies come from `script`, in order, or from `respond` for content-dependent answers."""

    def __init__(
        self,
        script: list[Reply] | None = None,
        respond: Callable[[dict[str, Any]], Reply] | None = None,
        catalog: list[dict[str, Any]] | httpx.Response | None = None,
    ) -> None:
        self.script = list(script or [])
        self.respond = respond
        self.catalog = catalog
        self.bodies: list[dict[str, Any]] = []
        self.catalog_requests = 0

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/endpoints/zdr"):
            self.catalog_requests += 1
            if isinstance(self.catalog, httpx.Response):
                return self.catalog
            return httpx.Response(200, json={"data": self.catalog or catalog_entries(load_routes())})
        body = json.loads(request.content)
        self.bodies.append(body)
        reply = self.respond(body) if self.respond else self.script.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=BASE_URL, transport=httpx.MockTransport(self.handle))

    def gateway(self, http: httpx.AsyncClient) -> ModelGateway:
        return ModelGateway(http, load_routes(), RetentionCatalog(http))
