"""Proves a model's endpoints meet Prism's retention rules before claim data is sent (D-027, D-057)."""

import time
from collections import defaultdict
from dataclasses import dataclass

import httpx
import structlog

from prism_ai.gateway.errors import ModelUnavailable, RouteNotPermitted

logger = structlog.get_logger()

CATALOG_PATH = "endpoints/zdr"
CATALOG_TTL_SECONDS = 3600.0


@dataclass(frozen=True)
class ModelRetention:
    endpoints: int
    with_safety_retention: int


class RetentionCatalog:
    """OpenRouter's public list of zero-data-retention endpoints, cached for an hour.

    `provider.zdr` already restricts routing to these endpoints. This adds the rule that ZDR alone
    does not give: an endpoint marked `safety_retention` may keep flagged content, which is not
    acceptable for health data. A model qualifies only if none of its ZDR endpoints do that, since
    the request cannot choose among them. An unreadable catalog stops processing: it fails closed.
    """

    def __init__(self, http: httpx.AsyncClient) -> None:
        self._http = http
        self._models: dict[str, ModelRetention] = {}
        self._fetched_at = float("-inf")

    async def require_permitted(self, model: str) -> None:
        if time.monotonic() - self._fetched_at > CATALOG_TTL_SECONDS:
            await self._refresh()
        retention = self._models.get(model)
        if retention is None:
            raise RouteNotPermitted(f"{model} has no zero-data-retention endpoint")
        if retention.with_safety_retention:
            raise RouteNotPermitted(f"{model} has endpoints that retain flagged content")

    async def _refresh(self) -> None:
        try:
            response = await self._http.get(CATALOG_PATH)
            response.raise_for_status()
            entries = response.json()["data"]
            counts: dict[str, list[int]] = defaultdict(lambda: [0, 0])
            for entry in entries:
                totals = counts[entry["model_id"]]
                totals[0] += 1
                # A missing flag is unknown rather than safe, so it counts against the model.
                totals[1] += 0 if entry.get("safety_retention") is False else 1
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as error:
            logger.warning("retention_catalog_unavailable", reason=type(error).__name__)
            raise ModelUnavailable("Retention catalog is unavailable") from error
        self._models = {name: ModelRetention(total, flagged) for name, (total, flagged) in counts.items()}
        self._fetched_at = time.monotonic()
