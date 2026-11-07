"""The only path to model providers. Everything sent to a model goes through `ModelGateway`."""

import json
import math
import time
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any

import httpx
import structlog
from pydantic import ValidationError

from prism_ai.gateway.errors import (
    ModelOutputInvalid,
    ModelRejected,
    ModelUnavailable,
    ProcessingError,
    RouteNotPermitted,
)
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import Route, RouteTable, Task
from prism_ai.gateway.usage import ModelRun, RunOutcome

logger = structlog.get_logger()

COMPLETIONS_PATH = "chat/completions"
EMBEDDINGS_PATH = "embeddings"
RERANK_PATH = "rerank"
EMBEDDING_TASK = "embedding"
RERANK_TASK = "rerank"
# Embedding and reranking have no prompt. Their runs still name one, so that every run has one shape.
NO_PROMPT = "none"
# Both are needed: "deny" alone was seen routing to endpoints outside the ZDR list.
PRIVATE_ROUTING = {"zdr": True, "data_collection": "deny"}
RETRYABLE_STATUS = frozenset({408, 409, 425, 429})

Message = dict[str, Any]


@dataclass(frozen=True)
class StructuredRequest[T]:
    """`parse` turns decoded JSON into the typed result, raising ValueError or ValidationError."""

    task: Task
    prompt_version: str
    schema_name: str
    schema: dict[str, Any]
    messages: list[Message]
    parse: Callable[[Any], T]


@dataclass(frozen=True)
class _Reply:
    content: str
    finish_reason: str | None
    provider: str | None
    input_tokens: int | None
    output_tokens: int | None
    cost_usd: Decimal | None
    latency_ms: int


def request_body(
    model: str, route: Route, request: StructuredRequest[Any], messages: list[Message]
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": 0,
        "max_tokens": route.max_tokens,
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": request.schema_name, "strict": True, "schema": request.schema},
        },
        "usage": {"include": True},
    }
    if route.claim_data:
        body["provider"] = PRIVATE_ROUTING
    return body


class ModelGateway:
    def __init__(self, http: httpx.AsyncClient, routes: RouteTable, retention: RetentionCatalog) -> None:
        self._http = http
        self._routes = routes
        self._retention = retention

    @property
    def embedding_model(self) -> str:
        return self._routes.embedding.model

    @property
    def rerank_model(self) -> str:
        return self._routes.rerank.model

    async def complete[T](self, request: StructuredRequest[T], runs: list[ModelRun]) -> T:
        """Returns validated output. Invalid output gets one repair, then one escalated attempt.

        Each attempt is appended to `runs`, so that the caller keeps usage even if this raises.
        """
        route = self._routes.route(request.task)
        if not route.claim_data:
            raise RouteNotPermitted(f"Route for {request.task} is not cleared for claim data", runs)

        attempt = 0
        problem = "no attempt was made"
        messages = request.messages
        for model, is_repair in ((route.primary, False), (route.primary, True), (route.escalation, False)):
            attempt += 1
            await self._require_permitted(model, runs)
            reply = await self._call(model, route, request, messages, attempt, runs)
            try:
                result = self._parse(reply, request)
            except ValueError as error:
                problem = str(error)
                runs.append(self._run(request, model, attempt, RunOutcome.INVALID_OUTPUT, reply))
                logger.warning("model_output_invalid", task=request.task, model=model, attempt=attempt)
                # A repair sees the failed output. An escalation starts clean, on a stronger model.
                messages = request.messages if is_repair else self._repair_messages(request, reply, problem)
                continue
            runs.append(self._run(request, model, attempt, RunOutcome.SUCCEEDED, reply))
            return result
        raise ModelOutputInvalid(f"Output for {request.task} stayed invalid: {problem}", runs)

    async def embed(
        self, texts: list[str], runs: list[ModelRun], model: str | None = None
    ) -> list[list[float]]:
        """Embeds texts in order, in batches. `model` defaults to the route's. It is given when a
        query must match the model that built an index."""
        route = self._routes.embedding
        if not route.claim_data:
            raise RouteNotPermitted("The embedding route is not cleared for claim data", runs)
        chosen = model or route.model
        await self._require_permitted(chosen, runs)
        vectors: list[list[float]] = []
        for start in range(0, len(texts), route.batch_size):
            batch = texts[start : start + route.batch_size]
            vectors += await self._embed_batch(chosen, batch, route.dimensions, runs)
        return vectors

    async def _embed_batch(
        self, model: str, texts: list[str], dimensions: int, runs: list[ModelRun]
    ) -> list[list[float]]:
        started = time.monotonic()
        failed = self._recorder(EMBEDDING_TASK, model, started, runs)
        body = {
            "model": model,
            "input": texts,
            "dimensions": dimensions,
            "encoding_format": "float",
            "provider": PRIVATE_ROUTING,
        }
        payload = await self._post(EMBEDDINGS_PATH, body, model, failed, runs)
        try:
            ordered = sorted(payload["data"], key=lambda item: int(item["index"]))
            vectors = [[float(number) for number in item["embedding"]] for item in ordered]
            if len(vectors) != len(texts) or any(
                len(vector) != dimensions or not all(map(math.isfinite, vector)) for vector in vectors
            ):
                raise ValueError("wrong shape")
        except (KeyError, TypeError, ValueError) as error:
            failed(RunOutcome.INVALID_OUTPUT)
            raise ModelUnavailable(f"{model} returned unusable embeddings", runs) from error
        runs.append(self._usage_run(EMBEDDING_TASK, model, payload, started))
        return vectors

    async def rerank(self, query: str, documents: list[str], runs: list[ModelRun]) -> list[float]:
        """A relevance score for each document, in the order given."""
        route = self._routes.rerank
        if not route.claim_data:
            raise RouteNotPermitted("The rerank route is not cleared for claim data", runs)
        await self._require_permitted(route.model, runs)
        started = time.monotonic()
        failed = self._recorder(RERANK_TASK, route.model, started, runs)
        body = {
            "model": route.model,
            "query": query,
            "documents": documents,
            "top_n": len(documents),
            "provider": PRIVATE_ROUTING,
        }
        payload = await self._post(RERANK_PATH, body, route.model, failed, runs)
        try:
            scores = {int(item["index"]): float(item["relevance_score"]) for item in payload["results"]}
            if sorted(scores) != list(range(len(documents))) or not all(map(math.isfinite, scores.values())):
                raise ValueError("wrong shape")
        except (KeyError, TypeError, ValueError) as error:
            failed(RunOutcome.INVALID_OUTPUT)
            raise ModelUnavailable(f"{route.model} returned unusable scores", runs) from error
        runs.append(self._usage_run(RERANK_TASK, route.model, payload, started))
        return [scores[index] for index in range(len(documents))]

    @staticmethod
    def _recorder(
        task: str, model: str, started: float, runs: list[ModelRun]
    ) -> Callable[[RunOutcome], None]:
        def failed(outcome: RunOutcome) -> None:
            runs.append(
                ModelRun(
                    task=task,
                    model=model,
                    provider=None,
                    prompt_version=NO_PROMPT,
                    attempt=1,
                    outcome=outcome,
                    input_tokens=None,
                    output_tokens=None,
                    cost_usd=None,
                    latency_ms=int((time.monotonic() - started) * 1000),
                )
            )

        return failed

    @staticmethod
    def _usage_run(task: str, model: str, payload: dict[str, Any], started: float) -> ModelRun:
        usage = payload.get("usage") or {}
        return ModelRun(
            task=task,
            model=model,
            provider=payload.get("provider"),
            prompt_version=NO_PROMPT,
            attempt=1,
            outcome=RunOutcome.SUCCEEDED,
            input_tokens=usage.get("prompt_tokens", usage.get("total_tokens")),
            output_tokens=None,
            cost_usd=_cost(usage.get("cost")),
            latency_ms=int((time.monotonic() - started) * 1000),
        )

    async def _require_permitted(self, model: str, runs: list[ModelRun]) -> None:
        try:
            await self._retention.require_permitted(model)
        except ProcessingError as error:
            error.runs = runs
            raise

    @staticmethod
    def _repair_messages(request: StructuredRequest[Any], reply: _Reply, problem: str) -> list[Message]:
        return [
            *request.messages,
            {"role": "assistant", "content": reply.content},
            {
                "role": "user",
                "content": f"That output was rejected: {problem[:500]}. Return corrected JSON only, "
                "following the schema exactly. Do not add values that are not on the document.",
            },
        ]

    @staticmethod
    def _parse[T](reply: _Reply, request: StructuredRequest[T]) -> T:
        if reply.finish_reason == "length":
            raise ValueError("output was cut off at the token limit")
        try:
            return request.parse(json.loads(reply.content))
        except ValidationError as error:
            # Field paths only: the error's input values may contain document content.
            fields = sorted({".".join(str(part) for part in item["loc"]) for item in error.errors()})
            raise ValueError(f"schema validation failed at {fields[:10]}") from None
        except json.JSONDecodeError:
            raise ValueError("output was not valid JSON") from None

    async def _call(
        self,
        model: str,
        route: Route,
        request: StructuredRequest[Any],
        messages: list[Message],
        attempt: int,
        runs: list[ModelRun],
    ) -> _Reply:
        started = time.monotonic()

        def failed(outcome: RunOutcome) -> None:
            runs.append(
                ModelRun(
                    task=request.task,
                    model=model,
                    provider=None,
                    prompt_version=request.prompt_version,
                    attempt=attempt,
                    outcome=outcome,
                    input_tokens=None,
                    output_tokens=None,
                    cost_usd=None,
                    latency_ms=int((time.monotonic() - started) * 1000),
                )
            )

        payload = await self._post(
            COMPLETIONS_PATH, request_body(model, route, request, messages), model, failed, runs
        )
        try:
            return self._read_reply(payload, int((time.monotonic() - started) * 1000))
        except (KeyError, IndexError, TypeError, ValueError) as error:
            # OpenRouter reports upstream failures inside a 200 response.
            failed(RunOutcome.UNAVAILABLE)
            raise ModelUnavailable(f"{model} returned an unusable response", runs) from error

    async def _post(
        self,
        path: str,
        body: dict[str, Any],
        model: str,
        failed: Callable[[RunOutcome], None],
        runs: list[ModelRun],
    ) -> dict[str, Any]:
        """Sends one request and classifies any failure. Response bodies are never logged: a
        provider error can echo the request."""
        try:
            response = await self._http.post(path, json=body)
        except httpx.HTTPError as error:
            failed(RunOutcome.UNAVAILABLE)
            raise ModelUnavailable(f"{type(error).__name__} calling {model}", runs) from error
        status = response.status_code
        if status >= 500 or status in RETRYABLE_STATUS:
            failed(RunOutcome.UNAVAILABLE)
            raise ModelUnavailable(f"{model} returned {status}", runs)
        if status >= 400:
            failed(RunOutcome.REJECTED)
            raise ModelRejected(f"{model} refused the request with {status}", runs)
        try:
            payload = response.json()
        except ValueError as error:
            failed(RunOutcome.UNAVAILABLE)
            raise ModelUnavailable(f"{model} returned an unusable response", runs) from error
        # OpenRouter reports upstream failures inside a 200 response.
        if not isinstance(payload, dict) or "error" in payload:
            failed(RunOutcome.UNAVAILABLE)
            raise ModelUnavailable(f"{model} returned an upstream error", runs)
        return payload

    @staticmethod
    def _read_reply(payload: dict[str, Any], latency_ms: int) -> _Reply:
        choice = payload["choices"][0]
        if "error" in choice:
            raise ValueError("upstream error")
        usage = payload.get("usage") or {}
        return _Reply(
            content=choice["message"].get("content") or "",
            finish_reason=choice.get("finish_reason"),
            provider=payload.get("provider"),
            input_tokens=usage.get("prompt_tokens"),
            output_tokens=usage.get("completion_tokens"),
            cost_usd=_cost(usage.get("cost")),
            latency_ms=latency_ms,
        )

    @staticmethod
    def _run(
        request: StructuredRequest[Any], model: str, attempt: int, outcome: RunOutcome, reply: _Reply
    ) -> ModelRun:
        return ModelRun(
            task=request.task,
            model=model,
            provider=reply.provider,
            prompt_version=request.prompt_version,
            attempt=attempt,
            outcome=outcome,
            input_tokens=reply.input_tokens,
            output_tokens=reply.output_tokens,
            cost_usd=reply.cost_usd,
            latency_ms=reply.latency_ms,
        )


def _cost(value: Any) -> Decimal | None:
    if value is None:
        return None
    try:
        return Decimal(str(value))
    except InvalidOperation:
        return None
