"""Contracts for the search index: what core sends at publication, and what a search returns."""

from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import Field, StringConstraints, model_validator

from prism_ai.documents.schemas import ApiModel
from prism_ai.gateway.usage import ModelRun
from prism_ai.policies.schemas import ProvisionType, RelationType

MAX_PROVISIONS = 5000
MAX_PROVISION_CHARS = 100_000
MAX_QUERY_CHARS = 500
MAX_RESULTS = 20


class IndexProvision(ApiModel):
    """A published provision, with core's ID, so that results link back to its review and source."""

    id: UUID
    key: str | None = Field(default=None, max_length=40)
    type: ProvisionType
    title: str = Field(max_length=500)
    text: str = Field(min_length=1, max_length=MAX_PROVISION_CHARS)
    page_start: int = Field(ge=1)
    page_end: int = Field(ge=1)
    parent_key: str | None = Field(default=None, max_length=40)


class IndexRelation(ApiModel):
    """`from` cites `to`, which excepts, qualifies, limits, defines, or is referred to by it."""

    from_provision_id: UUID
    to_provision_id: UUID
    relation: RelationType


class IndexRequest(ApiModel):
    version_id: UUID
    policy_id: UUID
    provisions: list[IndexProvision] = Field(min_length=1, max_length=MAX_PROVISIONS)
    relations: list[IndexRelation] = Field(max_length=MAX_PROVISIONS * 10)

    @model_validator(mode="after")
    def relations_join_provisions(self) -> IndexRequest:
        ids = [provision.id for provision in self.provisions]
        if len(set(ids)) != len(ids):
            raise ValueError("Provision IDs must be unique")
        known = set(ids)
        if any({link.from_provision_id, link.to_provision_id} - known for link in self.relations):
            raise ValueError("Every relation must join provisions in this version")
        return self


class IndexResponse(ApiModel):
    version_id: UUID
    chunk_count: int
    embedding_model: str
    pipeline_version: str
    runs: list[ModelRun]


Query = Annotated[str, StringConstraints(strip_whitespace=True, min_length=2, max_length=MAX_QUERY_CHARS)]


class SearchRequest(ApiModel):
    version_id: UUID
    query: Query
    types: list[ProvisionType] = Field(default_factory=list, max_length=len(ProvisionType))
    limit: int = Field(default=10, ge=1, le=MAX_RESULTS)


class ExactMatch(StrEnum):
    """Why a provision was looked up directly: its clause number, or its defined term, is in the query."""

    CLAUSE = "CLAUSE"
    TERM = "TERM"


class Direction(StrEnum):
    """OUTGOING: the result cites the related provision. INCOMING: the related provision cites it."""

    OUTGOING = "OUTGOING"
    INCOMING = "INCOMING"


class DegradedReason(StrEnum):
    SEMANTIC_UNAVAILABLE = "SEMANTIC_UNAVAILABLE"
    RERANK_UNAVAILABLE = "RERANK_UNAVAILABLE"


class StageScore(ApiModel):
    rank: int
    score: float


class RelatedLink(ApiModel):
    provision_id: UUID
    relation: RelationType
    direction: Direction


class SearchResult(ApiModel):
    """One provision, with the chunk that matched and its rank and score at every stage."""

    provision_id: UUID
    chunk_id: UUID
    key: str | None
    type: ProvisionType
    title: str
    section_path: list[str]
    page_start: int
    page_end: int
    text: str
    part: int
    parts: int
    final_rank: int
    pinned: bool
    exact: ExactMatch | None
    semantic: StageScore | None
    lexical: StageScore | None
    fused: StageScore
    rerank: StageScore | None
    related: list[RelatedLink]


class StageHit(ApiModel):
    provision_id: UUID
    key: str | None
    type: ProvisionType
    title: str
    rank: int
    score: float


class RelatedProvision(ApiModel):
    provision_id: UUID
    key: str | None
    type: ProvisionType
    title: str
    page_start: int
    page_end: int
    text: str


class SearchResponse(ApiModel):
    version_id: UUID
    degraded: list[DegradedReason]
    embedding_model: str
    rerank_model: str | None
    results: list[SearchResult]
    semantic: list[StageHit]
    lexical: list[StageHit]
    related: list[RelatedProvision]
    runs: list[ModelRun]
