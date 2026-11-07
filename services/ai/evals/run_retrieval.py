"""Measures retrieval against the golden set, using real embedding and rerank models. Costs a little
money; run on demand. Needs the test database settings in .env, as the search tests do.

    python -m uv run python evals/run_retrieval.py [--embedding-model <openrouter model>]

Every policy in the corpus is indexed in a scratch database, Gold v1 and v2 included, so that a
leak between versions would show. Recall@10 is reported for semantic only, lexical only, fused,
and the final reranked list. Targets are in docs/testing.md.
"""

import argparse
import asyncio
import json
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

EVALS = Path(__file__).parent
sys.path.insert(0, str(EVALS.parent))

from tests.support.knowledge_db import create_knowledge_database  # noqa: E402

from prism_ai.database import create_pool  # noqa: E402
from prism_ai.gateway.client import ModelGateway  # noqa: E402
from prism_ai.gateway.errors import ModelUnavailable  # noqa: E402
from prism_ai.gateway.retention import RetentionCatalog  # noqa: E402
from prism_ai.gateway.routes import load_routes  # noqa: E402
from prism_ai.gateway.usage import ModelRun  # noqa: E402
from prism_ai.main import model_http_client  # noqa: E402
from prism_ai.policies.schemas import ProvisionType, RelationType  # noqa: E402
from prism_ai.retrieval.pipeline import RetrievalPipeline  # noqa: E402
from prism_ai.retrieval.schemas import (  # noqa: E402
    IndexProvision,
    IndexRelation,
    IndexRequest,
    SearchRequest,
    SearchResponse,
)
from prism_ai.retrieval.store import IndexStore  # noqa: E402
from prism_ai.serve import event_loop_factory  # noqa: E402
from prism_ai.settings import get_settings  # noqa: E402

TARGET_RECALL = 0.90
VARIANTS = ("semantic", "lexical", "fused", "final")


class WithoutRerank(ModelGateway):
    """The fused order, as search returns it when the reranker is down."""

    async def rerank(self, query: str, documents: list[str], runs: list[ModelRun]) -> list[float]:
        raise ModelUnavailable("Reranking is switched off for this measurement", runs)


@dataclass
class Tally:
    required: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    found: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    by_kind: dict[str, list[int]] = field(default_factory=lambda: defaultdict(lambda: [0, 0]))
    related_total: int = 0
    related_found: int = 0
    leaks: int = 0
    degraded: int = 0
    misses: list[str] = field(default_factory=list)
    cost_usd: Decimal = Decimal(0)
    latencies_ms: list[int] = field(default_factory=list)


def index_request(rows: list[list[Any]]) -> tuple[IndexRequest, dict[UUID, str]]:
    ids = {row[0]: uuid4() for row in rows}
    provisions = [
        IndexProvision(
            id=ids[key],
            key=key,
            type=ProvisionType(kind),
            title=title,
            text=text,
            page_start=page,
            page_end=page,
        )
        for key, kind, title, page, text, _ in rows
    ]
    relations = [
        IndexRelation(
            from_provision_id=ids[key], to_provision_id=ids[target], relation=RelationType(relation)
        )
        for key, *_, references in rows
        for target, relation in references
    ]
    request = IndexRequest(version_id=uuid4(), policy_id=uuid4(), provisions=provisions, relations=relations)
    return request, {value: key for key, value in ids.items()}


def cost(runs: list[ModelRun]) -> Decimal:
    return sum((run.cost_usd or Decimal(0) for run in runs), Decimal(0))


def score(
    tally: Tally, query: dict[str, Any], final: SearchResponse, fused: SearchResponse, keys: dict[UUID, str]
) -> None:
    lists = {
        "semantic": [hit.provision_id for hit in final.semantic],
        "lexical": [hit.provision_id for hit in final.lexical],
        "fused": [result.provision_id for result in fused.results],
        "final": [result.provision_id for result in final.results],
    }
    every = [
        *lists["semantic"],
        *lists["lexical"],
        *lists["final"],
        *[item.provision_id for item in final.related],
    ]
    tally.leaks += sum(provision not in keys for provision in every)
    required = query["required"]
    for variant, found in lists.items():
        names = {keys.get(provision) for provision in found[:10]}
        tally.required[variant] += len(required)
        tally.found[variant] += sum(key in names for key in required)
        if variant == "final":
            tally.by_kind[query["kind"]][0] += sum(key in names for key in required)
            tally.by_kind[query["kind"]][1] += len(required)
            missing = [key for key in required if key not in names]
            if missing:
                tally.misses.append(f"{query['policy']}: {query['query']!r} missed {missing}")
    available = {keys.get(provision) for provision in lists["final"]} | {
        keys.get(item.provision_id) for item in final.related
    }
    for key in query.get("related", []):
        tally.related_total += 1
        tally.related_found += key in available
    if final.degraded:
        tally.degraded += 1
        tally.misses.append(f"{query['query']!r} degraded: {[str(reason) for reason in final.degraded]}")


async def evaluate(embedding_model: str | None) -> tuple[Tally, str]:
    database = create_knowledge_database()
    if database is None:
        raise SystemExit("Configure the test database in .env first.")
    corpus = json.loads((EVALS / "retrieval_corpus.json").read_text(encoding="utf-8"))["policies"]
    queries = json.loads((EVALS / "retrieval_queries.json").read_text(encoding="utf-8"))["queries"]
    routes = load_routes()
    if embedding_model:
        routes = routes.model_copy(
            update={"embedding": routes.embedding.model_copy(update={"model": embedding_model})}
        )
    tally = Tally()
    async with create_pool(database) as pool, model_http_client(get_settings()) as http:
        store = IndexStore(pool)
        catalog = RetentionCatalog(http)
        pipeline = RetrievalPipeline(ModelGateway(http, routes, catalog), store)
        fused_only = RetrievalPipeline(WithoutRerank(http, routes, catalog), store)
        versions: dict[str, tuple[UUID, dict[UUID, str]]] = {}
        for name, rows in corpus.items():
            request, keys = index_request(rows)
            response = await pipeline.index(request)
            tally.cost_usd += cost(response.runs)
            versions[name] = (request.version_id, keys)
        for query in queries:
            version_id, keys = versions[query["policy"]]
            search = SearchRequest(version_id=version_id, query=query["query"])
            started = time.monotonic()
            final = await pipeline.search(search)
            tally.latencies_ms.append(int((time.monotonic() - started) * 1000))
            fused = await fused_only.search(search)
            tally.cost_usd += cost(final.runs) + cost(fused.runs)
            score(tally, query, final, fused, keys)
    return tally, routes.embedding.model


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--embedding-model")
    arguments = parser.parse_args()
    tally, model = asyncio.run(evaluate(arguments.embedding_model), loop_factory=event_loop_factory())

    recall = {variant: tally.found[variant] / tally.required[variant] for variant in VARIANTS}
    latencies = sorted(tally.latencies_ms)
    summary = {
        "run_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "embedding_model": model,
        "rerank_model": load_routes().rerank.model,
        "recall_at_10": {variant: round(value, 3) for variant, value in recall.items()},
        "final_recall_by_kind": {
            kind: f"{found}/{total}" for kind, (found, total) in sorted(tally.by_kind.items())
        },
        "related_found": f"{tally.related_found}/{tally.related_total}",
        "wrong_version_results": tally.leaks,
        "degraded_searches": tally.degraded,
        "search_latency_ms": {"median": latencies[len(latencies) // 2], "max": latencies[-1]},
        "cost_usd": str(tally.cost_usd),
        "misses": tally.misses,
    }
    print(json.dumps(summary, indent=2))
    results = EVALS / "results"
    results.mkdir(exist_ok=True)
    (results / f"retrieval-{model.replace('/', '-')}.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    passed = (
        recall["final"] >= TARGET_RECALL and tally.leaks == 0 and tally.related_found == tally.related_total
    )
    print("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
