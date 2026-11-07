from decimal import Decimal
from enum import StrEnum

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class RunOutcome(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    INVALID_OUTPUT = "INVALID_OUTPUT"
    UNAVAILABLE = "UNAVAILABLE"
    REJECTED = "REJECTED"


class ModelRun(BaseModel):
    """One attempt against a model. Holds usage only, never request or response content."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    task: str
    model: str
    provider: str | None
    prompt_version: str
    attempt: int
    outcome: RunOutcome
    input_tokens: int | None
    output_tokens: int | None
    cost_usd: Decimal | None
    latency_ms: int
