"""Contracts for policy page extraction: what models must return, and what core receives."""

from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import Base64Bytes, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from prism_ai.documents.schemas import MAX_CONTENT_CHARS, Grounding
from prism_ai.gateway.usage import ModelRun


class ProvisionType(StrEnum):
    SCHEDULE = "SCHEDULE"
    DEFINITION = "DEFINITION"
    BENEFIT = "BENEFIT"
    WAITING_PERIOD = "WAITING_PERIOD"
    EXCLUSION = "EXCLUSION"
    PROCEDURE = "PROCEDURE"
    CLAUSE = "CLAUSE"


class RelationType(StrEnum):
    QUALIFIES = "QUALIFIES"
    LIMITS = "LIMITS"
    EXCEPTS = "EXCEPTS"
    DEFINES = "DEFINES"
    REFERENCES = "REFERENCES"


class PolicyValueKind(StrEnum):
    AMOUNT = "AMOUNT"
    PERCENTAGE = "PERCENTAGE"
    DURATION = "DURATION"
    TEXT = "TEXT"


class ValueState(StrEnum):
    PRESENT = "PRESENT"
    UNCERTAIN = "UNCERTAIN"


class ValueProblem(StrEnum):
    NO_SOURCE = "NO_SOURCE"
    QUOTE_NOT_FOUND = "QUOTE_NOT_FOUND"
    VALUE_NOT_IN_QUOTE = "VALUE_NOT_IN_QUOTE"
    UNREADABLE_FORMAT = "UNREADABLE_FORMAT"
    SCANNED_PAGE = "SCANNED_PAGE"


# --- Model output. No nullable fields: providers cap them per schema (D-061). ---------------------


class ModelOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ReferenceOutput(ModelOutput):
    target_key: str
    relation: RelationType


class ValueOutput(ModelOutput):
    name: str
    raw: str
    kind: PolicyValueKind
    qualifier: str
    page: int
    quote: str


class ProvisionOutput(ModelOutput):
    key: str
    type: ProvisionType
    title: str
    text: str
    page: int
    quote: str
    parent_key: str
    exceptions: list[str]
    references: list[ReferenceOutput]
    values: list[ValueOutput]
    continues_on_next_page: bool
    continued_from_previous_page: bool


class PolicyPagesOutput(ModelOutput):
    provisions: list[ProvisionOutput]


def _strict_object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _enum(values: type[StrEnum]) -> dict[str, Any]:
    return {"type": "string", "enum": [item.value for item in values]}


_STRING = {"type": "string"}
_INTEGER = {"type": "integer"}

POLICY_PAGES_SCHEMA = _strict_object(
    {
        "provisions": {
            "type": "array",
            "items": _strict_object(
                {
                    "key": _STRING,
                    "type": _enum(ProvisionType),
                    "title": _STRING,
                    "text": _STRING,
                    "page": _INTEGER,
                    "quote": _STRING,
                    "parent_key": _STRING,
                    "exceptions": {"type": "array", "items": _STRING},
                    "references": {
                        "type": "array",
                        "items": _strict_object({"target_key": _STRING, "relation": _enum(RelationType)}),
                    },
                    "values": {
                        "type": "array",
                        "items": _strict_object(
                            {
                                "name": _STRING,
                                "raw": _STRING,
                                "kind": _enum(PolicyValueKind),
                                "qualifier": _STRING,
                                "page": _INTEGER,
                                "quote": _STRING,
                            }
                        ),
                    },
                    "continues_on_next_page": {"type": "boolean"},
                    "continued_from_previous_page": {"type": "boolean"},
                }
            ),
        }
    }
)


# --- API ----------------------------------------------------------------------------------------


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class PolicyPagesRequest(ApiModel):
    document_id: UUID
    media_type: Literal["application/pdf"]
    first_page: int = Field(ge=1)
    last_page: int = Field(ge=1)
    content: Base64Bytes = Field(max_length=MAX_CONTENT_CHARS * 2)


Region = tuple[float, float, float, float]


class ExtractedReference(ApiModel):
    target_key: str
    relation: RelationType
    # Whether the provision's own text cites the target. A reference the text does not support is
    # kept for review, but never treated as fact.
    stated_in_text: bool


class ExtractedValue(ApiModel):
    name: str
    kind: PolicyValueKind
    raw_value: str
    value: str | None
    unit: str | None
    qualifier: str | None
    state: ValueState
    page: int | None
    quote: str | None
    region: Region | None
    grounding: Grounding
    problem: ValueProblem | None


class ExtractedProvision(ApiModel):
    key: str | None
    type: ProvisionType
    title: str
    text: str
    page: int
    quote: str
    region: Region | None
    grounding: Grounding
    text_verified: bool
    parent_key: str | None
    exceptions: list[str]
    references: list[ExtractedReference]
    values: list[ExtractedValue]
    continues_on_next_page: bool
    continued_from_previous_page: bool


class PolicyPagesResponse(ApiModel):
    pipeline_version: str
    page_count: int
    first_page: int
    last_page: int
    provisions: list[ExtractedProvision]
    runs: list[ModelRun]
