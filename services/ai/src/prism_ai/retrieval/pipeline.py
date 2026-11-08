"""Builds a version's search index, and searches it ([flow](docs/system-flows.md#rag-retrieval)).

Search runs three retrievers over one version: semantic (pgvector), lexical (full text), and exact
lookups for named clauses and defined terms. Their rankings are fused, the top candidates are
reranked, and each result brings the provisions related to it. A failed embedding or rerank does
not fail the search: the result says which stage is missing, and callers decide what that means.
"""

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from uuid import UUID

import structlog

from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.errors import ProcessingError
from prism_ai.gateway.usage import ModelRun
from prism_ai.retrieval import chunker
from prism_ai.retrieval.fusion import Ranked, by_provision, fuse
from prism_ai.retrieval.schemas import (
    DegradedReason,
    Direction,
    ExactMatch,
    IndexRequest,
    IndexResponse,
    RelatedLink,
    RelatedProvision,
    SearchRequest,
    SearchResponse,
    SearchResult,
    StageHit,
    StageScore,
)
from prism_ai.retrieval.store import ChunkRow, IndexStore, ProvisionRow, RelationRow

logger = structlog.get_logger()

PIPELINE_VERSION = "retrieval-1"
RETRIEVER_DEPTH = 30
RERANK_DEPTH = 20
STAGE_LIST_LENGTH = 10
# Long chunks are cut for the reranker only, which reads the start of a clause most closely anyway.
RERANK_CHARS = 2400


@dataclass(frozen=True)
class Stages:
    semantic: list[Ranked]
    lexical: list[Ranked]
    exact: dict[UUID, ExactMatch]
    exact_ranked: list[Ranked]


class RetrievalPipeline:
    def __init__(self, gateway: ModelGateway, store: IndexStore) -> None:
        self._gateway = gateway
        self._store = store

    async def index(self, request: IndexRequest) -> IndexResponse:
        """Chunks and embeds before opening a transaction, so that no model call holds one open."""
        runs: list[ModelRun] = []
        chunks, paths = chunker.chunk(request.provisions)
        vectors = await self._gateway.embed([chunk.search_text() for chunk in chunks], runs)
        await self._store.replace(
            request, chunks, vectors, paths, self._gateway.embedding_model, PIPELINE_VERSION
        )
        logger.info("policy_indexed", version_id=str(request.version_id), chunks=len(chunks))
        return IndexResponse(
            version_id=request.version_id,
            chunk_count=len(chunks),
            embedding_model=self._gateway.embedding_model,
            pipeline_version=PIPELINE_VERSION,
            runs=runs,
        )

    async def provisions(self, version_id: UUID, provision_ids: list[UUID]) -> list[ProvisionRow]:
        """Whole provisions of one version, for callers that must check quotes against them."""
        if not provision_ids:
            return []
        return await self._store.provisions(version_id, provision_ids)

    async def search(self, request: SearchRequest) -> SearchResponse:
        index = await self._store.index(request.version_id)
        runs: list[ModelRun] = []
        degraded: list[DegradedReason] = []
        stages = await self._retrieve(request, index.embedding_model, runs, degraded)
        fused = fuse({"semantic": stages.semantic, "lexical": stages.lexical, "exact": stages.exact_ranked})
        candidates = fused[:RERANK_DEPTH]
        chunk_ids = list({entry.chunk_id for entry in [*candidates, *stages.semantic, *stages.lexical]})
        rows = await self._store.chunks(request.version_id, chunk_ids)

        reranked = await self._rerank(request.query, candidates, rows, runs, degraded)
        ordered = self._final_order(candidates, reranked, stages.exact)[: request.limit]
        links = await self._store.relations(request.version_id, [entry.provision_id for entry in ordered])
        results = [
            self._result(entry, position, rows, stages, fused, reranked, links)
            for position, entry in enumerate(ordered, start=1)
        ]
        related_ids = {link.provision_id for result in results for link in result.related}
        related = await self._store.provisions(request.version_id, list(related_ids))
        return SearchResponse(
            version_id=request.version_id,
            degraded=degraded,
            embedding_model=index.embedding_model,
            rerank_model=None
            if DegradedReason.RERANK_UNAVAILABLE in degraded or not candidates
            else self._gateway.rerank_model,
            results=results,
            semantic=self._stage_hits(stages.semantic, rows),
            lexical=self._stage_hits(stages.lexical, rows),
            related=[
                RelatedProvision(
                    provision_id=row.provision_id,
                    key=row.key,
                    type=row.type,
                    title=row.title,
                    page_start=row.page_start,
                    page_end=row.page_end,
                    text=row.text,
                )
                for row in related
            ],
            runs=runs,
        )

    async def _retrieve(
        self,
        request: SearchRequest,
        embedding_model: str,
        runs: list[ModelRun],
        degraded: list[DegradedReason],
    ) -> Stages:
        version, types = request.version_id, request.types
        lexical, exact = await asyncio.gather(
            self._store.lexical(version, request.query, types, RETRIEVER_DEPTH),
            self._store.exact(version, request.query, types),
        )
        semantic: list[Ranked] = []
        try:
            vectors = await once_more(
                lambda: self._gateway.embed([request.query], runs, model=embedding_model)
            )
            vector = vectors[0]
        except ProcessingError as error:
            logger.warning("semantic_search_unavailable", reason=error.code, detail=str(error))
            degraded.append(DegradedReason.SEMANTIC_UNAVAILABLE)
        else:
            semantic = by_provision(await self._store.semantic(version, vector, types, RETRIEVER_DEPTH))
        return Stages(
            semantic=semantic,
            lexical=by_provision(lexical),
            exact={hit.hit.provision_id: hit.match for hit in exact},
            exact_ranked=by_provision([hit.hit for hit in exact]),
        )

    async def _rerank(
        self,
        query: str,
        candidates: list[Ranked],
        rows: dict[UUID, ChunkRow],
        runs: list[ModelRun],
        degraded: list[DegradedReason],
    ) -> dict[UUID, StageScore]:
        if not candidates:
            return {}
        documents = [
            f"{rows[entry.chunk_id].heading}\n{rows[entry.chunk_id].body}"[:RERANK_CHARS]
            for entry in candidates
        ]
        try:
            scores = await once_more(lambda: self._gateway.rerank(query, documents, runs))
        except ProcessingError as error:
            logger.warning("rerank_unavailable", reason=error.code, detail=str(error))
            degraded.append(DegradedReason.RERANK_UNAVAILABLE)
            return {}
        order = sorted(range(len(candidates)), key=lambda index: (-scores[index], candidates[index].rank))
        return {
            candidates[index].provision_id: StageScore(rank=rank, score=scores[index])
            for rank, index in enumerate(order, start=1)
        }

    @staticmethod
    def _final_order(
        candidates: list[Ranked], reranked: dict[UUID, StageScore], exact: dict[UUID, ExactMatch]
    ) -> list[Ranked]:
        """Reranked order, or fused order when reranking failed. A clause named by number comes first:
        the person asked for it, whatever a model thinks of its wording."""

        def position(entry: Ranked) -> tuple[int, int]:
            pinned = 0 if exact.get(entry.provision_id) == ExactMatch.CLAUSE else 1
            score = reranked.get(entry.provision_id)
            return (pinned, score.rank if score else entry.rank)

        return sorted(candidates, key=position)

    @staticmethod
    def _result(
        entry: Ranked,
        final_rank: int,
        rows: dict[UUID, ChunkRow],
        stages: Stages,
        fused: list[Ranked],
        reranked: dict[UUID, StageScore],
        links: list[RelationRow],
    ) -> SearchResult:
        row = rows[entry.chunk_id]

        def stage(ranking: list[Ranked]) -> StageScore | None:
            found = next((item for item in ranking if item.provision_id == entry.provision_id), None)
            return StageScore(rank=found.rank, score=found.score) if found else None

        fused_entry = stage(fused)
        assert fused_entry is not None  # noqa: S101 - every candidate came from the fused list
        match = stages.exact.get(entry.provision_id)
        return SearchResult(
            provision_id=row.provision_id,
            chunk_id=row.chunk_id,
            key=row.key,
            type=row.type,
            title=row.title,
            section_path=row.section_path,
            page_start=row.page_start,
            page_end=row.page_end,
            text=row.body,
            part=row.part,
            parts=row.parts,
            final_rank=final_rank,
            pinned=match == ExactMatch.CLAUSE,
            exact=match,
            semantic=stage(stages.semantic),
            lexical=stage(stages.lexical),
            fused=fused_entry,
            rerank=reranked.get(entry.provision_id),
            related=related_links(entry.provision_id, links),
        )

    @staticmethod
    def _stage_hits(ranking: list[Ranked], rows: dict[UUID, ChunkRow]) -> list[StageHit]:
        return [
            StageHit(
                provision_id=entry.provision_id,
                key=rows[entry.chunk_id].key,
                type=rows[entry.chunk_id].type,
                title=rows[entry.chunk_id].title,
                rank=entry.rank,
                score=entry.score,
            )
            for entry in ranking[:STAGE_LIST_LENGTH]
        ]


async def once_more[T](call: Callable[[], Awaitable[T]]) -> T:
    """Search is interactive, with no workflow to retry it, so a transient model failure gets one
    immediate second attempt before the stage is dropped. Measured at about one search in fifty."""
    try:
        return await call()
    except ProcessingError as error:
        if not error.retryable:
            raise
        logger.info("model_call_retried", reason=error.code, detail=str(error))
        return await call()


def related_links(provision_id: UUID, links: list[RelationRow]) -> list[RelatedLink]:
    """One hop in both directions. An exclusion needs the exception it cites, and an exception needs
    the exclusion that cites it, so either one found brings the other."""
    related: list[RelatedLink] = []
    for link in links:
        if link.from_provision_id == provision_id and link.to_provision_id != provision_id:
            related.append(
                RelatedLink(
                    provision_id=link.to_provision_id, relation=link.relation, direction=Direction.OUTGOING
                )
            )
        elif link.to_provision_id == provision_id and link.from_provision_id != provision_id:
            related.append(
                RelatedLink(
                    provision_id=link.from_provision_id, relation=link.relation, direction=Direction.INCOMING
                )
            )
    return related
