"""Contracts: what models must return, and what this service returns to core."""

from enum import StrEnum
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import Base64Bytes, BaseModel, BeforeValidator, ConfigDict, Field
from pydantic.alias_generators import to_camel

from prism_ai.documents.catalog import DocumentType, FieldSpec, ValueKind
from prism_ai.gateway.usage import ModelRun

MediaType = Literal["application/pdf", "image/png", "image/jpeg", "image/tiff"]
# The 25 MB document limit, as base64, with room for the JSON around it.
MAX_CONTENT_CHARS = 36_000_000


class ValueState(StrEnum):
    PRESENT = "PRESENT"
    MISSING = "MISSING"
    UNCERTAIN = "UNCERTAIN"
    CONFLICTING = "CONFLICTING"


class Confidence(StrEnum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class Grounding(StrEnum):
    TEXT_MATCH = "TEXT_MATCH"
    PAGE_ONLY = "PAGE_ONLY"
    NONE = "NONE"


class UncertaintyReason(StrEnum):
    MODEL_UNSURE = "MODEL_UNSURE"
    NO_SOURCE = "NO_SOURCE"
    QUOTE_NOT_FOUND = "QUOTE_NOT_FOUND"
    VALUE_NOT_IN_QUOTE = "VALUE_NOT_IN_QUOTE"
    UNREADABLE_FORMAT = "UNREADABLE_FORMAT"
    REREAD_DISAGREES = "REREAD_DISAGREES"
    REREAD_UNAVAILABLE = "REREAD_UNAVAILABLE"
    PAGES_DISAGREE = "PAGES_DISAGREE"


# --- Model output -------------------------------------------------------------------------------


class ModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")


def _blank_to_none(value: Any) -> Any:
    """Providers cap nullable parameters per schema, so "none" travels as "" or 0, and becomes a
    real null here. Nothing past this boundary sees the wire convention."""
    return None if value in ("", 0) else value


OptionalText = Annotated[str | None, BeforeValidator(_blank_to_none)]
OptionalPage = Annotated[int | None, BeforeValidator(_blank_to_none)]


class ClassificationOutput(ModelOutput):
    document_type: DocumentType
    confidence: Confidence


class FieldOutput(ModelOutput):
    state: Literal["PRESENT", "MISSING", "UNCERTAIN"]
    value: OptionalText
    confidence: Confidence
    page: OptionalPage
    quote: OptionalText


class LineItemOutput(ModelOutput):
    description: str
    quantity: OptionalText
    unit_price: OptionalText
    amount: str
    page: int
    quote: str


class ExtractionOutput(ModelOutput):
    fields: dict[str, FieldOutput]
    line_items: list[LineItemOutput]


class RereadValue(ModelOutput):
    value: OptionalText


class RereadOutput(ModelOutput):
    fields: dict[str, RereadValue]


def _strict_object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


CONFIDENCE_SCHEMA = {"type": "string", "enum": [item.value for item in Confidence]}

CLASSIFICATION_SCHEMA = _strict_object(
    {
        "document_type": {"type": "string", "enum": [item.value for item in DocumentType]},
        "confidence": CONFIDENCE_SCHEMA,
    }
)

FIELD_SCHEMA = _strict_object(
    {
        "state": {"type": "string", "enum": ["PRESENT", "MISSING", "UNCERTAIN"]},
        "value": {"type": "string"},
        "confidence": CONFIDENCE_SCHEMA,
        "page": {"type": "integer"},
        "quote": {"type": "string"},
    }
)

LINE_ITEM_SCHEMA = _strict_object(
    {
        "description": {"type": "string"},
        "quantity": {"type": "string"},
        "unit_price": {"type": "string"},
        "amount": {"type": "string"},
        "page": {"type": "integer"},
        "quote": {"type": "string"},
    }
)


def extraction_schema(specs: tuple[FieldSpec, ...]) -> dict[str, Any]:
    return _strict_object(
        {
            "fields": _strict_object({spec.name: FIELD_SCHEMA for spec in specs}),
            "line_items": {"type": "array", "items": LINE_ITEM_SCHEMA},
        }
    )


def reread_schema(specs: tuple[FieldSpec, ...]) -> dict[str, Any]:
    value = _strict_object({"value": {"type": "string"}})
    return _strict_object({"fields": _strict_object({spec.name: value for spec in specs})})


# --- API ----------------------------------------------------------------------------------------


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class ExtractRequest(ApiModel):
    document_id: UUID
    declared_type: DocumentType
    media_type: MediaType
    # Bounded before decoding. Base64Bytes then rejects anything that is not valid base64.
    content: Base64Bytes = Field(max_length=MAX_CONTENT_CHARS)


class Classification(ApiModel):
    document_type: DocumentType
    confidence: Confidence
    declared_type: DocumentType


class ExtractedField(ApiModel):
    name: str
    kind: ValueKind
    critical: bool
    state: ValueState
    confidence: Confidence
    raw_value: str | None
    value: str | None
    page: int | None
    quote: str | None
    region: tuple[float, float, float, float] | None
    grounding: Grounding
    reason: UncertaintyReason | None


class ExtractedLineItem(ApiModel):
    description: str
    quantity: str | None
    unit_price: str | None
    amount: str | None
    raw_amount: str
    state: ValueState
    page: int
    quote: str
    region: tuple[float, float, float, float] | None
    grounding: Grounding


class ExtractResponse(ApiModel):
    pipeline_version: str
    page_count: int
    classification: Classification
    fields: list[ExtractedField]
    line_items: list[ExtractedLineItem]
    runs: list[ModelRun]


class ProcessingFailure(ApiModel):
    code: str
    retryable: bool
    runs: list[ModelRun]
