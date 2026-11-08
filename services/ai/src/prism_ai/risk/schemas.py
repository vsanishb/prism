"""Contracts for risk screening support. Requests carry numbers and de-identified signal text only:
never names, hospitals, policy numbers, or document content (D-082, D-086)."""

from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import Field, field_validator

from prism_ai.documents.schemas import ApiModel, ModelOutput
from prism_ai.gateway.usage import ModelRun
from prism_ai.risk.detectors import Detector
from prism_ai.risk.features import FEATURE_NAMES, FEATURE_VERSION

MAX_SUMMARY_CHARS = 900


class FeatureInput(ApiModel):
    name: str
    # Absent when the claim's facts could not give it. The baseline median stands in, and says so.
    value: float | None = Field(allow_inf_nan=False)


class AnomalyRequest(ApiModel):
    claim_id: UUID
    feature_version: Literal["risk-features-1"]
    features: list[FeatureInput]

    @field_validator("features")
    @classmethod
    def every_feature_once(cls, features: list[FeatureInput]) -> list[FeatureInput]:
        if sorted(feature.name for feature in features) != sorted(FEATURE_NAMES):
            raise ValueError(f"features must be exactly {', '.join(FEATURE_NAMES)}")
        return features


class DetectorResult(ApiModel):
    detector: Detector
    score: float
    threshold: float
    percentile: float
    flagged: bool


class FeatureResult(ApiModel):
    name: str
    value: float
    imputed: bool
    baseline_median: float
    baseline_percentile: float


class AnomalyResponse(ApiModel):
    claim_id: UUID
    model_version: str
    feature_version: str = FEATURE_VERSION
    generator_version: str
    fingerprint: str
    baseline_size: int
    detectors: list[DetectorResult]
    features: list[FeatureResult]


class SignalSource(StrEnum):
    RULE = "RULE"
    ISOLATION_FOREST = "ISOLATION_FOREST"
    LOCAL_OUTLIER_FACTOR = "LOCAL_OUTLIER_FACTOR"


class SignalKind(StrEnum):
    DATA_INCONSISTENCY = "DATA_INCONSISTENCY"
    RISK_INDICATOR = "RISK_INDICATOR"
    FRAUD_INDICATOR = "FRAUD_INDICATOR"
    STATISTICAL_ANOMALY = "STATISTICAL_ANOMALY"


class Signal(ApiModel):
    id: str = Field(pattern=r"^S[1-9][0-9]?$")
    source: SignalSource
    kind: SignalKind
    category: str = Field(max_length=40)
    severity: Literal["LOW", "MEDIUM", "HIGH"] | None
    title: str = Field(min_length=1, max_length=160)
    explanation: str = Field(min_length=1, max_length=500)
    contribution: int = Field(ge=0, le=100)


class ExplanationRequest(ApiModel):
    claim_id: UUID
    band: Literal["LOW", "MEDIUM", "HIGH"]
    score: int = Field(ge=0, le=100)
    route: Literal["STANDARD_REVIEW", "SENIOR_REVIEW"]
    signals: list[Signal] = Field(min_length=1, max_length=40)

    @field_validator("signals")
    @classmethod
    def unique_ids(cls, signals: list[Signal]) -> list[Signal]:
        if len({signal.id for signal in signals}) != len(signals):
            raise ValueError("signal ids must be unique")
        return signals


class ExplanationStatus(StrEnum):
    AVAILABLE = "AVAILABLE"
    UNAVAILABLE = "UNAVAILABLE"


class ExplanationResponse(ApiModel):
    claim_id: UUID
    status: ExplanationStatus
    text: str | None
    cited: list[str]
    problem: str | None
    prompt_version: str
    runs: list[ModelRun]


class SummaryOutput(ModelOutput):
    summary: str
    signals_cited: list[str]


SUMMARY_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "signals_cited": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "signals_cited"],
    "additionalProperties": False,
}
