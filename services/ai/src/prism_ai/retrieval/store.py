"""The search index in the `knowledge` schema.

Every read takes a version ID and filters on it in SQL, so that no query can reach another policy
or another version of the same policy. Isolation is enforced here, beneath the pipeline, and is
tested with identical wordings indexed under two versions.
"""

import re
from collections.abc import AsyncIterator, Mapping, Sequence
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import psycopg
import structlog
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool, PoolTimeout

from prism_ai.gateway.errors import ProcessingError
from prism_ai.policies.schemas import ProvisionType, RelationType
from prism_ai.retrieval.chunker import Chunk
from prism_ai.retrieval.fusion import Hit
from prism_ai.retrieval.schemas import ExactMatch, IndexRequest

logger = structlog.get_logger()

# Clause numbers are looked up only when the query names them as clauses, so that "2.5 lakh" is
# not mistaken for clause 2.5.
CLAUSE_REFERENCE = re.compile(
    r"\b(?:clause|section|sec\.?|article|para(?:graph)?)\s*(\d{1,3}(?:\.\d{1,3}){0,3})\b", re.I
)
# Tokens with digits, such as clause numbers and codes, are matched as written as well as stemmed.
CODE_TOKEN = re.compile(r"\b(?=[a-z0-9.]*\d)[a-z0-9]+(?:\.[a-z0-9]+)*\b")
# How close a defined term must be to some part of the query, by pg_trgm word similarity.
TERM_SIMILARITY = 0.6


class IndexUnavailable(ProcessingError):
    code = "knowledge_store_unavailable"
    retryable = True
    status_code = 503


class IndexNotFound(ProcessingError):
    code = "index_not_found"
    status_code = 404


@dataclass(frozen=True)
class IndexInfo:
    version_id: UUID
    embedding_model: str


@dataclass(frozen=True)
class ExactHit:
    hit: Hit
    match: ExactMatch


@dataclass(frozen=True)
class ChunkRow:
    chunk_id: UUID
    provision_id: UUID
    key: str | None
    type: ProvisionType
    title: str
    section_path: list[str]
    page_start: int
    page_end: int
    heading: str
    body: str
    part: int
    parts: int


@dataclass(frozen=True)
class ProvisionRow:
    provision_id: UUID
    key: str | None
    type: ProvisionType
    title: str
    page_start: int
    page_end: int
    text: str


@dataclass(frozen=True)
class RelationRow:
    from_provision_id: UUID
    to_provision_id: UUID
    relation: RelationType


def vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(repr(number) for number in vector) + "]"


def code_tokens(query: str) -> list[str]:
    return sorted(set(CODE_TOKEN.findall(query.lower())))


def clause_references(query: str) -> list[str]:
    return list(dict.fromkeys(CLAUSE_REFERENCE.findall(query)))


def _types(types: list[ProvisionType]) -> list[str] | None:
    return [str(kind) for kind in types] or None


class IndexStore:
    def __init__(self, pool: AsyncConnectionPool) -> None:
        self._pool = pool

    @asynccontextmanager
    async def _connection(self) -> AsyncIterator[psycopg.AsyncConnection[Any]]:
        try:
            async with self._pool.connection(timeout=10) as connection:
                yield connection
        except (psycopg.OperationalError, PoolTimeout) as error:
            logger.warning("knowledge_store_unavailable", reason=type(error).__name__)
            raise IndexUnavailable("The search index is unavailable") from error

    async def _rows(self, sql: str, params: Sequence[Any] | Mapping[str, Any]) -> list[dict[str, Any]]:
        async with self._connection() as connection, connection.cursor(row_factory=dict_row) as cursor:
            await cursor.execute(sql, params)
            return await cursor.fetchall()

    async def replace(
        self,
        request: IndexRequest,
        chunks: list[Chunk],
        vectors: list[list[float]],
        paths: dict[UUID, list[str]],
        embedding_model: str,
        pipeline_version: str,
    ) -> None:
        """Swaps in the whole index for one version, in one transaction: a search sees the old index
        or the new one, never a mix."""
        async with self._connection() as connection, connection.transaction():
            version = request.version_id
            await connection.execute("DELETE FROM knowledge.search_indexes WHERE version_id = %s", (version,))
            await connection.execute(
                """INSERT INTO knowledge.search_indexes
                       (version_id, policy_id, embedding_model, dimensions, pipeline_version, chunk_count)
                   VALUES (%s, %s, %s, %s, %s, %s)""",
                (version, request.policy_id, embedding_model, len(vectors[0]), pipeline_version, len(chunks)),
            )
            async with connection.cursor() as cursor:
                await cursor.executemany(
                    """INSERT INTO knowledge.indexed_provisions
                           (id, version_id, provision_key, type, title, text,
                            page_start, page_end, section_path)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
                    [
                        (
                            p.id,
                            version,
                            p.key,
                            str(p.type),
                            p.title,
                            p.text,
                            p.page_start,
                            p.page_end,
                            paths[p.id],
                        )
                        for p in request.provisions
                    ],
                )
                await cursor.executemany(
                    """INSERT INTO knowledge.indexed_relations
                           (version_id, from_provision_id, to_provision_id, relation)
                       VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING""",
                    [
                        (version, r.from_provision_id, r.to_provision_id, str(r.relation))
                        for r in request.relations
                    ],
                )
                await cursor.executemany(
                    """INSERT INTO knowledge.chunks
                           (version_id, provision_id, part, parts, heading, body, embedding, embedding_model)
                       VALUES (%s, %s, %s, %s, %s, %s, %s::vector, %s)""",
                    [
                        (
                            version,
                            c.provision_id,
                            c.part,
                            c.parts,
                            c.heading,
                            c.body,
                            vector_literal(v),
                            embedding_model,
                        )
                        for c, v in zip(chunks, vectors, strict=True)
                    ],
                )

    async def index(self, version_id: UUID) -> IndexInfo:
        rows = await self._rows(
            "SELECT embedding_model FROM knowledge.search_indexes WHERE version_id = %s", (version_id,)
        )
        row = rows[0] if rows else None
        if row is None:
            raise IndexNotFound("This version has no search index")
        return IndexInfo(version_id, row["embedding_model"])

    async def semantic(
        self, version_id: UUID, vector: list[float], types: list[ProvisionType], depth: int
    ) -> list[Hit]:
        """Exact cosine distance over the version's chunks (D-024). Score is cosine similarity."""
        rows = await self._rows(
            """SELECT c.id, c.provision_id, 1 - (c.embedding <=> %(vector)s::vector) AS score
               FROM knowledge.chunks c JOIN knowledge.indexed_provisions p ON p.id = c.provision_id
               WHERE c.version_id = %(version)s
                 AND (%(types)s::text[] IS NULL OR p.type = ANY(%(types)s::text[]))
               ORDER BY c.embedding <=> %(vector)s::vector, c.id
               LIMIT %(depth)s""",
            {
                "vector": vector_literal(vector),
                "version": version_id,
                "types": _types(types),
                "depth": depth,
            },
        )
        return [Hit(row["id"], row["provision_id"], float(row["score"])) for row in rows]

    async def lexical(
        self, version_id: UUID, query: str, types: list[ProvisionType], depth: int
    ) -> list[Hit]:
        """Full-text search, ranked by cover density. Any query word may match: a clause rarely uses
        every word of a question about it. Words are stemmed, and codes also match as written."""
        codes = " | ".join(f"'{token}'" for token in code_tokens(query))
        rows = await self._rows(
            """WITH parts AS (
                   SELECT NULLIF(replace(plainto_tsquery('english', %(query)s)::text, ' & ', ' | '), '')
                              AS words,
                          NULLIF(%(codes)s, '') AS codes
               ), q AS (
                   SELECT CASE
                       WHEN words IS NULL AND codes IS NULL THEN NULL
                       WHEN codes IS NULL THEN words::tsquery
                       WHEN words IS NULL THEN to_tsquery('simple', codes)
                       ELSE words::tsquery || to_tsquery('simple', codes)
                   END AS query FROM parts
               )
               SELECT c.id, c.provision_id, ts_rank_cd(c.search_vector, q.query, 32) AS score
               FROM q, knowledge.chunks c JOIN knowledge.indexed_provisions p ON p.id = c.provision_id
               WHERE c.version_id = %(version)s AND q.query IS NOT NULL AND c.search_vector @@ q.query
                 AND (%(types)s::text[] IS NULL OR p.type = ANY(%(types)s::text[]))
               ORDER BY score DESC, c.id
               LIMIT %(depth)s""",
            {
                "query": query,
                "codes": codes,
                "version": version_id,
                "types": _types(types),
                "depth": depth,
            },
        )
        return [Hit(row["id"], row["provision_id"], float(row["score"])) for row in rows]

    async def exact(self, version_id: UUID, query: str, types: list[ProvisionType]) -> list[ExactHit]:
        """Provisions the query names: clauses by number, in the order named, then defined terms by
        trigram word similarity, so that "pre existing diseases" still finds "Pre-existing Disease"."""
        clauses = clause_references(query)
        rows = await self._rows(
            """WITH named AS (
                   SELECT p.id, 'CLAUSE' AS match,
                          array_position(%(clauses)s::text[], p.provision_key) AS ordering,
                          1.0::float8 AS score
                   FROM knowledge.indexed_provisions p
                   WHERE p.version_id = %(version)s AND p.provision_key = ANY(%(clauses)s::text[])
                   UNION ALL
                   SELECT p.id, 'TERM', 1000, word_similarity(
                              lower(regexp_replace(p.title, '^\\s*[0-9.]+\\s*', '')), lower(%(query)s))
                   FROM knowledge.indexed_provisions p
                   WHERE p.version_id = %(version)s AND p.type = 'DEFINITION'
                     AND word_similarity(
                              lower(regexp_replace(p.title, '^\\s*[0-9.]+\\s*', '')), lower(%(query)s))
                         >= %(similarity)s
               ), best AS (
                   SELECT DISTINCT ON (n.id) n.id, n.match, n.ordering, n.score
                   FROM named n ORDER BY n.id, n.ordering
               )
               SELECT b.match, c.id AS chunk_id, c.provision_id, b.score
               FROM best b
               JOIN knowledge.indexed_provisions p ON p.id = b.id
               JOIN knowledge.chunks c ON c.provision_id = b.id AND c.part = 1 AND c.version_id = %(version)s
               WHERE (%(types)s::text[] IS NULL OR p.type = ANY(%(types)s::text[]))
               ORDER BY b.ordering, b.score DESC, c.id""",
            {
                "clauses": clauses,
                "version": version_id,
                "query": query,
                "similarity": TERM_SIMILARITY,
                "types": _types(types),
            },
        )
        return [
            ExactHit(Hit(row["chunk_id"], row["provision_id"], float(row["score"])), ExactMatch(row["match"]))
            for row in rows
        ]

    async def chunks(self, version_id: UUID, chunk_ids: list[UUID]) -> dict[UUID, ChunkRow]:
        rows = await self._rows(
            """SELECT c.id, c.provision_id, p.provision_key, p.type, p.title, p.section_path, p.page_start,
                      p.page_end, c.heading, c.body, c.part, c.parts
               FROM knowledge.chunks c JOIN knowledge.indexed_provisions p ON p.id = c.provision_id
               WHERE c.version_id = %s AND c.id = ANY(%s)""",
            (version_id, chunk_ids),
        )
        return {
            row["id"]: ChunkRow(
                row["id"],
                row["provision_id"],
                row["provision_key"],
                ProvisionType(row["type"]),
                row["title"],
                list(row["section_path"]),
                row["page_start"],
                row["page_end"],
                row["heading"],
                row["body"],
                row["part"],
                row["parts"],
            )
            for row in rows
        }

    async def relations(self, version_id: UUID, provision_ids: list[UUID]) -> list[RelationRow]:
        rows = await self._rows(
            """SELECT from_provision_id, to_provision_id, relation FROM knowledge.indexed_relations
               WHERE version_id = %(version)s
                 AND (from_provision_id = ANY(%(ids)s) OR to_provision_id = ANY(%(ids)s))
               ORDER BY from_provision_id, to_provision_id, relation""",
            {"version": version_id, "ids": provision_ids},
        )
        return [
            RelationRow(row["from_provision_id"], row["to_provision_id"], RelationType(row["relation"]))
            for row in rows
        ]

    async def provisions(self, version_id: UUID, provision_ids: list[UUID]) -> list[ProvisionRow]:
        rows = await self._rows(
            """SELECT id, provision_key, type, title, page_start, page_end, text
               FROM knowledge.indexed_provisions WHERE version_id = %s AND id = ANY(%s)
               ORDER BY page_start, provision_key, id""",
            (version_id, provision_ids),
        )
        return [
            ProvisionRow(
                row["id"],
                row["provision_key"],
                ProvisionType(row["type"]),
                row["title"],
                row["page_start"],
                row["page_end"],
                row["text"],
            )
            for row in rows
        ]
