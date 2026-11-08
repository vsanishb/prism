"""Model routes, read from configuration and validated at startup."""

import tomllib
from enum import StrEnum
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

ROUTES_FILE = Path(__file__).with_name("routes.toml")


class Task(StrEnum):
    CLASSIFICATION = "classification"
    EXTRACTION = "extraction"
    REREAD = "reread"
    POLICY_EXTRACTION = "policy_extraction"
    AGENT_PLAN = "agent_plan"
    AGENT_ANALYSIS = "agent_analysis"
    RISK_SUMMARY = "risk_summary"


class Route(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    primary: str = Field(min_length=3)
    escalation: str = Field(min_length=3)
    claim_data: bool
    max_tokens: int = Field(gt=0, le=32000)

    def models(self) -> tuple[str, ...]:
        return (self.primary, self.escalation)


class EmbeddingRoute(BaseModel):
    """One model, with no escalation: a query must be embedded by the model that embedded the index."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    model: str = Field(min_length=3)
    # Fixed by the index's column type. Changing it needs a migration and a rebuild of every index.
    dimensions: Literal[1536]
    claim_data: bool
    batch_size: int = Field(gt=0, le=256)


class RerankRoute(BaseModel):
    """A failed rerank falls back to the fused order, marked degraded, rather than to another model."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    model: str = Field(min_length=3)
    claim_data: bool


class RouteTable(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    tasks: dict[Task, Route]
    embedding: EmbeddingRoute
    rerank: RerankRoute

    @model_validator(mode="after")
    def every_task_is_routed(self) -> RouteTable:
        missing = set(Task) - set(self.tasks)
        if missing:
            raise ValueError(f"No route for: {sorted(missing)}")
        return self

    def route(self, task: Task) -> Route:
        return self.tasks[task]

    def models(self) -> set[str]:
        """Every model a route can send to, for the retention catalog and test stubs."""
        return {model for route in self.tasks.values() for model in route.models()} | {
            self.embedding.model,
            self.rerank.model,
        }


def load_routes(path: Path = ROUTES_FILE) -> RouteTable:
    return RouteTable.model_validate(tomllib.loads(path.read_text(encoding="utf-8")))
