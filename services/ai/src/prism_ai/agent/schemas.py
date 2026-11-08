"""Contracts for the Policy Agent: what core sends, what models return, and what core receives.

Statuses describe evidence, never outcomes: there is deliberately no approve or reject.
"""

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Any
from uuid import UUID

from pydantic import Field, model_validator

from prism_ai.documents.schemas import ApiModel, ModelOutput
from prism_ai.gateway.usage import ModelRun
from prism_ai.policies.schemas import ProvisionType

PIPELINE_VERSION = "agent-1"

# Only facts needed to read a policy are sent: never names, hospitals, doctors, or policy numbers.
# Core sends no others, and anything else is refused here too (security.md, data minimization).
ALLOWED_FACTS = frozenset(
    {
        "admission_date",
        "discharge_date",
        "claimed_amount",
        "diagnosis",
        "procedure",
        "test_name",
        "findings",
        "medicines",
    }
)


class IssueKind(StrEnum):
    COVERAGE = "COVERAGE"
    LIMIT = "LIMIT"
    WAITING_PERIOD = "WAITING_PERIOD"
    EXCLUSION = "EXCLUSION"
    COST_SHARING = "COST_SHARING"
    CONDITION = "CONDITION"


class FindingStatus(StrEnum):
    SUPPORTED = "SUPPORTED"
    LIMITED = "LIMITED"
    POTENTIALLY_EXCLUDED = "POTENTIALLY_EXCLUDED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    NEEDS_HUMAN_INTERPRETATION = "NEEDS_HUMAN_INTERPRETATION"


# These rest on what the wording says, so each needs a valid citation.
EVIDENCED_STATUSES = frozenset(
    {FindingStatus.SUPPORTED, FindingStatus.LIMITED, FindingStatus.POTENTIALLY_EXCLUDED}
)


class ValueRole(StrEnum):
    """How a confirmed policy value applies. Core computes with it; the model only names it."""

    WAITING_PERIOD = "WAITING_PERIOD"
    AMOUNT_LIMIT = "AMOUNT_LIMIT"
    DAILY_LIMIT = "DAILY_LIMIT"
    PERCENT_OF_SUM_INSURED = "PERCENT_OF_SUM_INSURED"
    COPAYMENT = "COPAYMENT"
    DEDUCTIBLE = "DEDUCTIBLE"


ROLE_KINDS: dict[ValueRole, frozenset[str]] = {
    ValueRole.WAITING_PERIOD: frozenset({"DURATION"}),
    ValueRole.AMOUNT_LIMIT: frozenset({"AMOUNT"}),
    ValueRole.DAILY_LIMIT: frozenset({"AMOUNT", "PERCENTAGE"}),
    ValueRole.PERCENT_OF_SUM_INSURED: frozenset({"PERCENTAGE"}),
    ValueRole.COPAYMENT: frozenset({"PERCENTAGE"}),
    ValueRole.DEDUCTIBLE: frozenset({"AMOUNT"}),
}


class Problem(StrEnum):
    """Why code changed or rejected part of a model's finding. Stored, and shown to reviewers."""

    CITATION_UNKNOWN = "CITATION_UNKNOWN"
    CITATION_QUOTE_MISMATCH = "CITATION_QUOTE_MISMATCH"
    NO_VALID_CITATION = "NO_VALID_CITATION"
    FACT_UNKNOWN = "FACT_UNKNOWN"
    FACT_NOT_ESTABLISHED = "FACT_NOT_ESTABLISHED"
    VALUE_REJECTED = "VALUE_REJECTED"
    SEARCH_LIMIT_REACHED = "SEARCH_LIMIT_REACHED"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    ANALYSIS_FAILED = "ANALYSIS_FAILED"


# --- What core sends ---------------------------------------------------------------------------


class ClaimFact(ApiModel):
    id: UUID
    name: str
    kind: str = Field(pattern="^(TEXT|DATE|AMOUNT)$")
    state: str = Field(pattern="^(PRESENT|MISSING|UNCERTAIN|CONFLICTING)$")
    value: str | None = Field(default=None, max_length=2000)


class LineItem(ApiModel):
    id: UUID
    description: str = Field(max_length=500)
    amount: Decimal | None


class PolicyValue(ApiModel):
    """A value a person confirmed or corrected before publication. Only these are computed with."""

    id: UUID
    provision_id: UUID
    name: str = Field(max_length=200)
    kind: str = Field(pattern="^(AMOUNT|PERCENTAGE|DURATION|TEXT)$")
    value: str = Field(max_length=200)
    unit: str | None = Field(default=None, max_length=40)
    qualifier: str | None = Field(default=None, max_length=200)


class AnalysisRequest(ApiModel):
    claim_id: UUID
    version_id: UUID
    claim_type: str = Field(pattern="^(REIMBURSEMENT|CASHLESS)$")
    currency: str = Field(pattern="^[A-Z]{3}$")
    sum_insured: Decimal
    cover_start: date
    facts: list[ClaimFact] = Field(max_length=50)
    line_items: list[LineItem] = Field(max_length=200)
    policy_values: list[PolicyValue] = Field(max_length=1000)

    @model_validator(mode="after")
    def only_needed_facts(self) -> AnalysisRequest:
        extra = {fact.name for fact in self.facts} - ALLOWED_FACTS
        if extra:
            raise ValueError(f"Facts not needed for policy analysis: {sorted(extra)}")
        return self


# --- What models return. No nullable fields: providers cap them per schema (D-061). ------------


class PlannedIssue(ModelOutput):
    kind: IssueKind
    question: str
    queries: list[str]


class PlanOutput(ModelOutput):
    issues: list[PlannedIssue]


class CitationOutput(ModelOutput):
    evidence_id: str
    quote: str


class AppliedValueOutput(ModelOutput):
    value_id: str
    role: ValueRole


class AnalysisOutput(ModelOutput):
    action: str = Field(pattern="^(FINDING|SEARCH_MORE)$")
    follow_up_queries: list[str]
    status: FindingStatus
    facts_used: list[str]
    citations: list[CitationOutput]
    reasoning: str
    unresolved: list[str]
    applied_values: list[AppliedValueOutput]


def _strict_object(properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


def _enum(values: type[StrEnum]) -> dict[str, Any]:
    return {"type": "string", "enum": [item.value for item in values]}


_STRING: dict[str, Any] = {"type": "string"}
_STRINGS: dict[str, Any] = {"type": "array", "items": _STRING}

PLAN_SCHEMA = _strict_object(
    {
        "issues": {
            "type": "array",
            "items": _strict_object({"kind": _enum(IssueKind), "question": _STRING, "queries": _STRINGS}),
        }
    }
)

ANALYSIS_SCHEMA = _strict_object(
    {
        "action": {"type": "string", "enum": ["FINDING", "SEARCH_MORE"]},
        "follow_up_queries": _STRINGS,
        "status": _enum(FindingStatus),
        "facts_used": _STRINGS,
        "citations": {"type": "array", "items": _strict_object({"evidence_id": _STRING, "quote": _STRING})},
        "reasoning": _STRING,
        "unresolved": _STRINGS,
        "applied_values": {
            "type": "array",
            "items": _strict_object({"value_id": _STRING, "role": _enum(ValueRole)}),
        },
    }
)


# --- What core receives ------------------------------------------------------------------------


class Citation(ApiModel):
    provision_id: UUID
    key: str | None
    title: str
    type: ProvisionType
    quote: str


class AppliedValue(ApiModel):
    value_id: UUID
    role: ValueRole


class Finding(ApiModel):
    position: int
    kind: IssueKind
    question: str
    status: FindingStatus
    proposed_status: FindingStatus | None
    facts_used: list[UUID]
    citations: list[Citation]
    reasoning: str
    unresolved: list[str]
    applied_values: list[AppliedValue]
    problems: list[Problem]
    # Citations validation set aside, so that the model's own citation accuracy can be measured.
    rejected_citations: int
    rounds: int
    queries: list[str]


class AnalysisResponse(ApiModel):
    claim_id: UUID
    version_id: UUID
    pipeline_version: str
    plan_prompt_version: str
    analysis_prompt_version: str
    findings: list[Finding]
    degraded_searches: int
    runs: list[ModelRun]
