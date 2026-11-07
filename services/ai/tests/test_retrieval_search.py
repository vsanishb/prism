"""Indexing and search against a real PostgreSQL with pgvector, with scripted models.

The isolation tests index identical wordings under two versions: any leak between them would show.
"""

import asyncio
from collections.abc import Callable, Coroutine, Iterator
from typing import Any
from uuid import UUID, uuid4

import httpx
import pytest

from prism_ai.database import create_pool
from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import load_routes
from prism_ai.gateway.usage import RunOutcome
from prism_ai.policies.schemas import ProvisionType, RelationType
from prism_ai.retrieval.pipeline import RetrievalPipeline
from prism_ai.retrieval.schemas import (
    DegradedReason,
    Direction,
    ExactMatch,
    IndexProvision,
    IndexRelation,
    IndexRequest,
    SearchRequest,
    SearchResponse,
)
from prism_ai.retrieval.store import IndexNotFound, IndexStore
from prism_ai.serve import event_loop_factory
from tests.support.fake_models import embeddings_reply, rerank_reply
from tests.support.knowledge_db import create_knowledge_database
from tests.support.scripted import ScriptedProvider

LONG_EXCLUSION = (
    "Treatment received outside India is not covered. "
    + "This exclusion applies to every benefit, including Hospitalization and day care treatment. " * 25
    + "It does not apply to emergency evacuation to India within 48 hours of the first symptoms."
)

WORDING: list[tuple[str, ProvisionType, str, str]] = [
    (
        "1.3",
        ProvisionType.DEFINITION,
        "Pre-existing Disease",
        "Pre-existing Disease means any condition diagnosed within 48 months before the first policy.",
    ),
    (
        "2.6",
        ProvisionType.BENEFIT,
        "Cataract surgery",
        "Cataract surgery is covered up to INR 40,000 per eye, after the waiting period in Clause 3.2.",
    ),
    (
        "3.2",
        ProvisionType.WAITING_PERIOD,
        "Specific illnesses",
        "A 24-month waiting period applies to cataract, hernia, and kidney stones.",
    ),
    (
        "3.3",
        ProvisionType.WAITING_PERIOD,
        "Pre-existing diseases",
        "Pre-existing Diseases, as defined in Clause 1.3, are covered after 36 months.",
    ),
    (
        "4.1",
        ProvisionType.EXCLUSION,
        "Cosmetic treatment",
        "Cosmetic or aesthetic treatment is excluded, except as stated in Clause 4.6.",
    ),
    ("4.5", ProvisionType.EXCLUSION, "Treatment outside India", LONG_EXCLUSION),
    (
        "4.6",
        ProvisionType.CLAUSE,
        "Reconstruction",
        "Reconstruction following an accident or burns is covered.",
    ),
    (
        "5.1",
        ProvisionType.PROCEDURE,
        "Notification",
        "Notify the Insurer within 48 hours of an emergency admission.",
    ),
]
RELATIONS = [
    ("2.6", "3.2", RelationType.LIMITS),
    ("4.1", "4.6", RelationType.EXCEPTS),
    ("3.3", "1.3", RelationType.DEFINES),
]


def index_request(version_id: UUID | None = None) -> IndexRequest:
    provisions = [
        IndexProvision(id=uuid4(), key=key, type=kind, title=title, text=text, page_start=1, page_end=2)
        for key, kind, title, text in WORDING
    ]
    ids = {provision.key: provision.id for provision in provisions}
    return IndexRequest(
        version_id=version_id or uuid4(),
        policy_id=uuid4(),
        provisions=provisions,
        relations=[
            IndexRelation(from_provision_id=ids[source], to_provision_id=ids[target], relation=relation)
            for source, target, relation in RELATIONS
        ],
    )


def models(
    rerank: Callable[[dict[str, Any]], httpx.Response] | None = None, embed_fails: bool = False
) -> ScriptedProvider:
    def respond(body: dict[str, Any]) -> httpx.Response:
        if "documents" in body:
            return rerank(body) if rerank else httpx.Response(200, json=rerank_reply(body))
        if embed_fails and len(body["input"]) == 1:
            return httpx.Response(503, json={"error": {"code": 503}})
        return httpx.Response(200, json=embeddings_reply(body))

    return ScriptedProvider(respond=respond)


@pytest.fixture(scope="module")
def database() -> Iterator[str]:
    url = create_knowledge_database()
    if url is None:
        pytest.skip("No test database configured")
    yield url


def run[T](coroutine: Coroutine[Any, Any, T]) -> T:
    return asyncio.run(coroutine, loop_factory=event_loop_factory())


Scenario = Callable[[RetrievalPipeline, IndexStore], Coroutine[Any, Any, Any]]


def with_pipeline(database: str, provider: ScriptedProvider, scenario: Scenario) -> Any:
    async def body() -> Any:
        async with create_pool(database) as pool, provider.client() as http:
            store = IndexStore(pool)
            gateway = ModelGateway(http, load_routes(), RetentionCatalog(http))
            return await scenario(RetrievalPipeline(gateway, store), store)

    return run(body())


def indexed_search(
    database: str, query: str, provider: ScriptedProvider | None = None, **options: Any
) -> tuple[IndexRequest, SearchResponse]:
    request = index_request()

    async def scenario(pipeline: RetrievalPipeline, _store: IndexStore) -> SearchResponse:
        await pipeline.index(request)
        return await pipeline.search(SearchRequest(version_id=request.version_id, query=query, **options))

    return request, with_pipeline(database, provider or models(), scenario)


def keys(request: IndexRequest, ids: list[UUID]) -> list[str | None]:
    by_id = {provision.id: provision.key for provision in request.provisions}
    return [by_id[provision_id] for provision_id in ids]


def test_search_never_returns_another_version_even_with_identical_wording(database: str) -> None:
    first, second = index_request(), index_request()

    async def scenario(pipeline: RetrievalPipeline, _store: IndexStore) -> list[SearchResponse]:
        await pipeline.index(first)
        await pipeline.index(second)
        queries = ["cataract waiting period", "clause 4.1", "pre-existing disease", "treatment outside India"]
        return [
            await pipeline.search(SearchRequest(version_id=first.version_id, query=query))
            for query in queries
        ]

    responses = with_pipeline(database, models(), scenario)

    allowed = {provision.id for provision in first.provisions}
    for response in responses:
        found = {item.provision_id for item in [*response.results, *response.semantic, *response.lexical]}
        found |= {link.provision_id for result in response.results for link in result.related}
        found |= {item.provision_id for item in response.related}
        assert found, "each query should find something"
        assert found <= allowed


def test_a_clause_named_by_number_comes_first(database: str) -> None:
    request, response = indexed_search(database, "what does clause 4.5 say about evacuation")

    top = response.results[0]
    assert keys(request, [top.provision_id]) == ["4.5"]
    assert top.pinned and top.exact == ExactMatch.CLAUSE


def test_a_defined_term_is_found_even_when_misspelled(database: str) -> None:
    request, response = indexed_search(database, "pre existing diseas")

    terms = [result for result in response.results if result.exact == ExactMatch.TERM]
    assert keys(request, [result.provision_id for result in terms]) == ["1.3"]


def test_clause_numbers_match_as_written_in_full_text(database: str) -> None:
    request, response = indexed_search(database, "1.3")
    assert "3.3" in keys(request, [hit.provision_id for hit in response.lexical])


def test_an_exclusion_brings_its_exception_and_an_exception_brings_its_exclusion(database: str) -> None:
    request, response = indexed_search(database, "cosmetic aesthetic treatment")
    cosmetic = next(result for result in response.results if keys(request, [result.provision_id]) == ["4.1"])
    exception = next(link for link in cosmetic.related if link.relation == RelationType.EXCEPTS)
    assert keys(request, [exception.provision_id]) == ["4.6"] and exception.direction == Direction.OUTGOING
    assert "4.6" in keys(request, [item.provision_id for item in response.related])

    request, response = indexed_search(database, "reconstruction after burns")
    reconstruction = next(
        result for result in response.results if keys(request, [result.provision_id]) == ["4.6"]
    )
    assert [(keys(request, [link.provision_id]), link.direction) for link in reconstruction.related] == [
        (["4.1"], Direction.INCOMING)
    ]


def test_a_long_provision_is_found_by_the_part_that_matches(database: str) -> None:
    request, response = indexed_search(database, "emergency evacuation to India within 48 hours")
    outside = next(result for result in response.results if keys(request, [result.provision_id]) == ["4.5"])
    assert outside.parts > 1
    assert "emergency evacuation" in outside.text
    assert outside.text in LONG_EXCLUSION
    assert len({result.provision_id for result in response.results}) == len(response.results)


def test_a_type_filter_applies_to_every_retriever(database: str) -> None:
    _, response = indexed_search(database, "clause 2.6 cataract", types=[ProvisionType.WAITING_PERIOD])
    assert response.results
    assert {result.type for result in response.results} == {ProvisionType.WAITING_PERIOD}
    assert {hit.type for hit in [*response.semantic, *response.lexical]} <= {ProvisionType.WAITING_PERIOD}


def test_a_rerank_outage_returns_fused_results_marked_degraded(database: str) -> None:
    outage = models(rerank=lambda _body: httpx.Response(503, json={"error": {"code": 503}}))
    _, response = indexed_search(database, "cataract surgery limit", provider=outage)

    assert response.degraded == [DegradedReason.RERANK_UNAVAILABLE]
    assert response.rerank_model is None
    assert response.results
    assert all(result.rerank is None for result in response.results)
    assert [result.fused.rank for result in response.results] == sorted(
        result.fused.rank for result in response.results
    )
    assert any(run.task == "rerank" and run.outcome == RunOutcome.UNAVAILABLE for run in response.runs)


def test_an_embedding_outage_still_returns_full_text_results(database: str) -> None:
    _, response = indexed_search(database, "cataract surgery", provider=models(embed_fails=True))

    assert response.degraded == [DegradedReason.SEMANTIC_UNAVAILABLE]
    assert response.semantic == []
    assert response.results


def test_reranking_decides_the_final_order(database: str) -> None:
    _, response = indexed_search(database, "kidney stones hernia waiting")
    assert response.rerank_model == load_routes().rerank.model
    assert [result.rerank.rank for result in response.results if result.rerank] == list(
        range(1, len(response.results) + 1)
    )


def test_indexing_again_replaces_the_index_without_duplicates(database: str) -> None:
    request = index_request()

    async def scenario(pipeline: RetrievalPipeline, _store: IndexStore) -> tuple[int, int, SearchResponse]:
        first = await pipeline.index(request)
        second = await pipeline.index(request)
        response = await pipeline.search(SearchRequest(version_id=request.version_id, query="cataract"))
        return first.chunk_count, second.chunk_count, response

    first, second, response = with_pipeline(database, models(), scenario)
    assert first == second > len(request.provisions)
    assert len({result.provision_id for result in response.results}) == len(response.results)


def test_a_query_is_embedded_with_the_model_that_built_the_index(database: str) -> None:
    request = index_request()
    provider = models()
    indexing_model = load_routes().embedding.model

    async def scenario(_pipeline: RetrievalPipeline, store: IndexStore) -> None:
        async with provider.client() as http:
            routes = load_routes()
            changed = routes.model_copy(
                update={"embedding": routes.embedding.model_copy(update={"model": "other/embedder"})}
            )
            catalog = [
                {"model_id": model, "safety_retention": False}
                for model in {*routes.models(), "other/embedder"}
            ]
            provider.catalog = catalog
            await RetrievalPipeline(ModelGateway(http, routes, RetentionCatalog(http)), store).index(request)
            later = RetrievalPipeline(ModelGateway(http, changed, RetentionCatalog(http)), store)
            await later.search(SearchRequest(version_id=request.version_id, query="cataract"))

    with_pipeline(database, provider, scenario)
    assert [body["model"] for body in provider.bodies if "input" in body][-1] == indexing_model


def test_searching_a_version_without_an_index_is_refused(database: str) -> None:
    async def scenario(pipeline: RetrievalPipeline, _store: IndexStore) -> None:
        await pipeline.search(SearchRequest(version_id=uuid4(), query="cataract"))

    with pytest.raises(IndexNotFound):
        with_pipeline(database, models(), scenario)


def test_an_index_request_with_a_relation_outside_the_version_is_refused() -> None:
    request = index_request()
    with pytest.raises(ValueError, match="join provisions"):
        IndexRequest(
            version_id=request.version_id,
            policy_id=request.policy_id,
            provisions=request.provisions,
            relations=[
                IndexRelation(
                    from_provision_id=request.provisions[0].id,
                    to_provision_id=uuid4(),
                    relation=RelationType.REFERENCES,
                )
            ],
        )


def test_one_transient_rerank_failure_is_retried_and_not_degraded(database: str) -> None:
    failures = iter([True])

    def flaky(body: dict[str, Any]) -> httpx.Response:
        if next(failures, False):
            return httpx.Response(503, json={"error": {"code": 503}})
        return httpx.Response(200, json=rerank_reply(body))

    _, response = indexed_search(database, "cataract surgery limit", provider=models(rerank=flaky))

    assert response.degraded == []
    assert [run.outcome for run in response.runs if run.task == "rerank"] == [
        RunOutcome.UNAVAILABLE,
        RunOutcome.SUCCEEDED,
    ]
