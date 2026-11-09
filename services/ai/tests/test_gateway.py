"""Failure paths of the model gateway. Claim data must never reach a route that cannot be proven
safe, and every failure must be classified so that the workflow knows whether to retry."""

import asyncio
import logging
from typing import Any

import httpx
import pytest
from pydantic import BaseModel, ConfigDict

from prism_ai.gateway.client import ModelGateway, StructuredRequest
from prism_ai.gateway.errors import ModelOutputInvalid, ModelRejected, ModelUnavailable, RouteNotPermitted
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import Route, Task, load_routes
from prism_ai.gateway.usage import ModelRun, RunOutcome
from tests.support.scripted import ScriptedProvider, catalog_entries, completion

ROUTES = load_routes()
ROUTE = ROUTES.route(Task.EXTRACTION)
GOOD = {"answer": "yes"}
DOCUMENT_TEXT = "Asha Kulkarni"


class Answer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str


def request() -> StructuredRequest[Answer]:
    return StructuredRequest(
        task=Task.EXTRACTION,
        prompt_version="test.v1",
        schema_name="answer",
        schema={"type": "object"},
        messages=[{"role": "user", "content": DOCUMENT_TEXT}],
        parse=Answer.model_validate,
    )


def complete(provider: ScriptedProvider, runs: list[ModelRun] | None = None) -> Answer:
    async def call() -> Answer:
        async with provider.client() as http:
            return await provider.gateway(http).complete(request(), [] if runs is None else runs)

    return asyncio.run(call())


def test_claim_data_requests_demand_zero_data_retention() -> None:
    provider = ScriptedProvider([completion(GOOD)])
    assert complete(provider).answer == "yes"
    assert provider.bodies[0]["provider"] == {"zdr": True, "data_collection": "deny"}


def test_a_route_not_cleared_for_claim_data_is_refused_before_any_call() -> None:
    tasks = dict(ROUTES.tasks)
    tasks[Task.EXTRACTION] = Route(
        primary="a/b", escalation="a/b", claim_data=False, max_tokens=10, reasoning=False
    )
    provider = ScriptedProvider([completion(GOOD)])

    async def call() -> None:
        async with provider.client() as http:
            gateway = ModelGateway(http, ROUTES.model_copy(update={"tasks": tasks}), RetentionCatalog(http))
            await gateway.complete(request(), [])

    with pytest.raises(RouteNotPermitted):
        asyncio.run(call())
    assert provider.bodies == []


@pytest.mark.parametrize(
    "entry",
    [
        {"model_id": ROUTE.primary, "safety_retention": True},
        # An absent flag is unknown, and unknown is treated as unsafe.
        {"model_id": ROUTE.primary},
    ],
)
def test_a_model_with_safety_retention_is_refused_before_any_call(entry: dict[str, Any]) -> None:
    provider = ScriptedProvider([completion(GOOD)], catalog=[entry])
    with pytest.raises(RouteNotPermitted):
        complete(provider)
    assert provider.bodies == []


def test_one_unsafe_endpoint_disqualifies_the_model() -> None:
    entries = [
        {"model_id": ROUTE.primary, "safety_retention": False},
        {"model_id": ROUTE.primary, "safety_retention": True},
    ]
    provider = ScriptedProvider([completion(GOOD)], catalog=entries)
    with pytest.raises(RouteNotPermitted):
        complete(provider)
    assert provider.bodies == []


def test_a_model_absent_from_the_catalog_is_refused() -> None:
    other = {"model_id": "other/model", "safety_retention": False}
    provider = ScriptedProvider([completion(GOOD)], catalog=[other])
    with pytest.raises(RouteNotPermitted):
        complete(provider)
    assert provider.bodies == []


def test_an_unreadable_catalog_fails_closed_and_can_be_retried() -> None:
    provider = ScriptedProvider([completion(GOOD)], catalog=httpx.Response(503))
    with pytest.raises(ModelUnavailable) as caught:
        complete(provider)
    assert caught.value.retryable
    assert provider.bodies == []


def test_the_catalog_is_cached_between_calls() -> None:
    provider = ScriptedProvider([completion(GOOD), completion(GOOD)])

    async def call() -> None:
        async with provider.client() as http:
            gateway = provider.gateway(http)
            await gateway.complete(request(), [])
            await gateway.complete(request(), [])

    asyncio.run(call())
    assert provider.catalog_requests == 1


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503])
def test_transient_statuses_are_retryable(status: int) -> None:
    runs: list[ModelRun] = []
    with pytest.raises(ModelUnavailable) as caught:
        complete(ScriptedProvider([httpx.Response(status)]), runs)
    assert caught.value.retryable
    assert [run.outcome for run in runs] == [RunOutcome.UNAVAILABLE]


def test_a_timeout_is_retryable() -> None:
    with pytest.raises(ModelUnavailable) as caught:
        complete(ScriptedProvider([httpx.ReadTimeout("slow")]))
    assert caught.value.retryable


@pytest.mark.parametrize("status", [400, 401, 402, 403, 404])
def test_refusals_are_not_retried(status: int) -> None:
    runs: list[ModelRun] = []
    with pytest.raises(ModelRejected) as caught:
        complete(ScriptedProvider([httpx.Response(status)]), runs)
    assert not caught.value.retryable
    assert [run.outcome for run in runs] == [RunOutcome.REJECTED]


@pytest.mark.parametrize(
    "payload",
    [{"error": {"code": 502}}, {"choices": []}, {"choices": [{"error": {"code": 500}}]}, {}],
)
def test_an_upstream_failure_inside_a_200_is_retryable(payload: dict[str, Any]) -> None:
    with pytest.raises(ModelUnavailable):
        complete(ScriptedProvider([httpx.Response(200, json=payload)]))


def test_malformed_output_gets_one_repair_that_sees_the_failure() -> None:
    provider = ScriptedProvider([completion("not json"), completion(GOOD)])
    runs: list[ModelRun] = []
    assert complete(provider, runs).answer == "yes"

    repair = provider.bodies[1]
    assert repair["model"] == ROUTE.primary
    assert repair["messages"][-2] == {"role": "assistant", "content": "not json"}
    assert "rejected" in repair["messages"][-1]["content"]
    assert [run.outcome for run in runs] == [RunOutcome.INVALID_OUTPUT, RunOutcome.SUCCEEDED]


def test_a_failed_repair_escalates_with_a_clean_request() -> None:
    provider = ScriptedProvider([completion("bad"), completion({"wrong": 1}), completion(GOOD)])
    runs: list[ModelRun] = []
    assert complete(provider, runs).answer == "yes"

    escalated = provider.bodies[2]
    assert escalated["model"] == ROUTE.escalation
    assert escalated["messages"] == request().messages
    assert [run.attempt for run in runs] == [1, 2, 3]


def test_output_invalid_after_escalation_fails_explicitly_and_keeps_usage() -> None:
    provider = ScriptedProvider([completion("bad"), completion("bad"), completion("bad")])
    runs: list[ModelRun] = []
    with pytest.raises(ModelOutputInvalid) as caught:
        complete(provider, runs)
    assert not caught.value.retryable
    assert len(caught.value.runs) == 3
    assert all(run.input_tokens == 100 for run in runs)


def test_output_cut_off_at_the_token_limit_is_invalid_even_if_it_parses() -> None:
    provider = ScriptedProvider([completion(GOOD, finish_reason="length"), completion(GOOD)])
    runs: list[ModelRun] = []
    complete(provider, runs)
    assert runs[0].outcome == RunOutcome.INVALID_OUTPUT


def test_reasoning_is_stated_on_every_request() -> None:
    provider = ScriptedProvider([completion(GOOD)])
    complete(provider)
    assert provider.bodies[0]["reasoning"] == {"enabled": ROUTE.reasoning}


def test_copying_tasks_run_without_reasoning() -> None:
    # Reasoning tokens count against max_tokens. With it on, DeepSeek V4.1 Flash spent a policy
    # batch's whole 16,000-token budget thinking and returned no answer, on every attempt.
    copying = (Task.CLASSIFICATION, Task.EXTRACTION, Task.REREAD, Task.POLICY_EXTRACTION)
    assert not any(ROUTES.route(task).reasoning for task in copying)


def test_the_policy_agent_reasons() -> None:
    # Without reasoning, the agent obeyed a document's injected "cite nothing" in one run of two.
    assert ROUTES.route(Task.AGENT_PLAN).reasoning
    assert ROUTES.route(Task.AGENT_ANALYSIS).reasoning


def test_usage_is_recorded_for_each_attempt() -> None:
    runs: list[ModelRun] = []
    complete(ScriptedProvider([completion(GOOD, provider="Amazon Bedrock")]), runs)
    run = runs[0]
    assert (run.model, run.provider, run.prompt_version) == (ROUTE.primary, "Amazon Bedrock", "test.v1")
    assert (run.input_tokens, run.output_tokens, str(run.cost_usd)) == (100, 20, "0.0012")


def test_repair_feedback_names_fields_but_not_their_values() -> None:
    leaked = {"answer": 5, "extra": DOCUMENT_TEXT}
    provider = ScriptedProvider([completion(leaked), completion(GOOD)])
    complete(provider)
    assert DOCUMENT_TEXT not in provider.bodies[1]["messages"][-1]["content"]


def test_failures_log_no_document_content(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    echoing = httpx.Response(400, json={"error": {"message": DOCUMENT_TEXT}})
    with pytest.raises(ModelRejected) as caught:
        complete(ScriptedProvider([echoing]))
    assert DOCUMENT_TEXT not in str(caught.value)
    assert DOCUMENT_TEXT not in caplog.text


def test_routes_cover_every_task_and_are_all_cleared_for_claim_data() -> None:
    assert set(ROUTES.tasks) == set(Task)
    assert all(route.claim_data for route in ROUTES.tasks.values())


def test_shipped_routes_avoid_models_known_to_retain() -> None:
    models = {entry["model_id"] for entry in catalog_entries(ROUTES)}
    assert "google/gemini-3.8-flash" not in models


def embed(provider: ScriptedProvider, texts: list[str], runs: list[ModelRun]) -> list[list[float]]:
    async def call() -> list[list[float]]:
        async with provider.client() as http:
            return await provider.gateway(http).embed(texts, runs)

    return asyncio.run(call())


def vectors(body: dict[str, Any], size: int = 1536) -> httpx.Response:
    data = [{"index": index, "embedding": [0.5] * size} for index in reversed(range(len(body["input"])))]
    return httpx.Response(200, json={"data": data, "usage": {"prompt_tokens": 3, "cost": 0.00001}})


def test_embeddings_are_private_batched_and_returned_in_input_order() -> None:
    provider = ScriptedProvider(respond=vectors)
    runs: list[ModelRun] = []
    texts = [f"text {index}" for index in range(ROUTES.embedding.batch_size + 1)]

    result = embed(provider, texts, runs)

    assert len(result) == len(texts)
    assert [len(body["input"]) for body in provider.bodies] == [ROUTES.embedding.batch_size, 1]
    assert all(body["provider"] == {"zdr": True, "data_collection": "deny"} for body in provider.bodies)
    assert all(body["dimensions"] == 1536 for body in provider.bodies)
    assert [run.outcome for run in runs] == [RunOutcome.SUCCEEDED, RunOutcome.SUCCEEDED]


def test_embeddings_of_the_wrong_size_are_refused() -> None:
    provider = ScriptedProvider(respond=lambda body: vectors(body, size=3))
    runs: list[ModelRun] = []
    with pytest.raises(ModelUnavailable):
        embed(provider, ["text"], runs)
    assert [run.outcome for run in runs] == [RunOutcome.INVALID_OUTPUT]


def test_an_embedding_model_with_safety_retention_is_refused_before_any_call() -> None:
    entries = [
        {"model_id": model, "safety_retention": model == ROUTES.embedding.model} for model in ROUTES.models()
    ]
    provider = ScriptedProvider(respond=vectors, catalog=entries)
    with pytest.raises(RouteNotPermitted):
        embed(provider, ["text"], [])
    assert provider.bodies == []


def test_rerank_scores_come_back_in_document_order() -> None:
    reply = {"results": [{"index": 1, "relevance_score": 0.9}, {"index": 0, "relevance_score": 0.1}]}
    provider = ScriptedProvider([httpx.Response(200, json=reply)])

    async def call() -> list[float]:
        async with provider.client() as http:
            return await provider.gateway(http).rerank("query", ["a", "b"], [])

    assert asyncio.run(call()) == [0.1, 0.9]
    assert provider.bodies[0]["provider"] == {"zdr": True, "data_collection": "deny"}


def test_rerank_that_skips_a_document_is_refused() -> None:
    reply = {"results": [{"index": 0, "relevance_score": 0.9}]}
    provider = ScriptedProvider([httpx.Response(200, json=reply)])
    runs: list[ModelRun] = []

    async def call() -> list[float]:
        async with provider.client() as http:
            return await provider.gateway(http).rerank("query", ["a", "b"], runs)

    with pytest.raises(ModelUnavailable):
        asyncio.run(call())
    assert [run.outcome for run in runs] == [RunOutcome.INVALID_OUTPUT]
