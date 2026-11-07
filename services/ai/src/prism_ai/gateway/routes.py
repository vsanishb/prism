"""Model routes, read from configuration and validated at startup."""

import tomllib
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator

ROUTES_FILE = Path(__file__).with_name("routes.toml")


class Task(StrEnum):
    CLASSIFICATION = "classification"
    EXTRACTION = "extraction"
    REREAD = "reread"
    POLICY_EXTRACTION = "policy_extraction"


class Route(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    primary: str = Field(min_length=3)
    escalation: str = Field(min_length=3)
    claim_data: bool
    max_tokens: int = Field(gt=0, le=32000)

    def models(self) -> tuple[str, ...]:
        return (self.primary, self.escalation)


class RouteTable(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    tasks: dict[Task, Route]

    @model_validator(mode="after")
    def every_task_is_routed(self) -> RouteTable:
        missing = set(Task) - set(self.tasks)
        if missing:
            raise ValueError(f"No route for: {sorted(missing)}")
        return self

    def route(self, task: Task) -> Route:
        return self.tasks[task]


def load_routes(path: Path = ROUTES_FILE) -> RouteTable:
    return RouteTable.model_validate(tomllib.loads(path.read_text(encoding="utf-8")))
