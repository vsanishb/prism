"""Measures claim extraction against the golden set, using real models. Costs money; run on demand.

    python -m uv run python evals/run_claim_extraction.py
    python -m uv run python evals/run_claim_extraction.py --extraction-model anthropic/claude-haiku-5.5

Targets are in docs/testing.md. Results go to evals/results/, and are recorded in the module doc.
"""

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import uuid4

from prism_ai.documents.catalog import ValueKind
from prism_ai.documents.ground import tokens
from prism_ai.documents.pipeline import PIPELINE_VERSION, DocumentPipeline
from prism_ai.documents.schemas import ExtractedField, ExtractRequest, ExtractResponse, ValueState
from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.errors import ModelUnavailable, ProcessingError
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import RouteTable, Task, load_routes
from prism_ai.main import model_http_client
from prism_ai.settings import get_settings

EVALS = Path(__file__).parent
TEST_DATA = EVALS.parents[2] / "test-data"
CONCURRENCY = 3
# Stands in for Temporal, which retries transient failures in the running system.
MAX_ATTEMPTS = 4
BACKOFF_SECONDS = 15
TARGETS = {"critical": 0.95, "monetary": 0.98}


@dataclass
class Tally:
    critical_total: int = 0
    critical_correct: int = 0
    monetary_total: int = 0
    monetary_correct: int = 0
    other_total: int = 0
    other_correct: int = 0
    absent_total: int = 0
    invented: int = 0
    classified: int = 0
    documents: int = 0
    failed_documents: int = 0
    line_item_cases: int = 0
    line_item_correct: int = 0
    injected_values: int = 0
    cost_usd: Decimal = Decimal(0)
    calls: int = 0
    problems: list[str] = field(default_factory=list)


def matches(result: ExtractedField, expected: str) -> bool:
    if result.state != ValueState.PRESENT or result.value is None:
        return False
    if result.kind != ValueKind.TEXT:
        return result.value == expected
    wanted, actual = tokens(expected), tokens(result.value)
    span = len(wanted)
    return any(actual[start : start + span] == wanted for start in range(len(actual) - span + 1))


def score_field(tally: Tally, case: dict[str, Any], result: ExtractedField, expected: str | None) -> None:
    name = f"{case['path']}:{result.name}"
    if expected is None:
        tally.absent_total += 1
        if result.state != ValueState.MISSING and (result.raw_value or result.value):
            tally.invented += 1
            tally.problems.append(f"INVENTED {name}: {result.state} {result.raw_value!r}")
        return
    correct = matches(result, expected)
    if result.kind == ValueKind.AMOUNT:
        tally.monetary_total += 1
        tally.monetary_correct += correct
    if result.critical:
        tally.critical_total += 1
        tally.critical_correct += correct
    else:
        tally.other_total += 1
        tally.other_correct += correct
    if not correct:
        tally.problems.append(
            f"WRONG {name}: expected {expected!r}, got {result.state} {result.value!r}"
            f" raw={result.raw_value!r} reason={result.reason}"
        )


def score_case(tally: Tally, case: dict[str, Any], response: ExtractResponse) -> None:
    if response.classification.document_type in case["acceptedTypes"]:
        tally.classified += 1
    else:
        tally.problems.append(f"TYPE {case['path']}: {response.classification.document_type}")
    results = {item.name: item for item in response.fields}
    for name, expected in case["fields"].items():
        if name in results:
            score_field(tally, case, results[name], expected)

    if "lineItems" in case:
        tally.line_item_cases += 1
        items = response.line_items
        total = sum((Decimal(item.amount) for item in items if item.amount), Decimal(0))
        trusted = all(item.state == ValueState.PRESENT for item in items)
        wanted = case["lineItems"]
        if len(items) == wanted["count"] and total == Decimal(wanted["sum"]) and trusted:
            tally.line_item_correct += 1
        else:
            tally.problems.append(
                f"LINES {case['path']}: {len(items)} items, sum {total}, trusted {trusted}, wanted {wanted}"
            )

    output = response.model_dump_json()
    for text in case.get("forbidden", []):
        if text in output:
            tally.injected_values += 1
            tally.problems.append(f"INJECTED {case['path']}: {text}")


async def run_case(
    pipeline: DocumentPipeline, case: dict[str, Any], tally: Tally, limit: asyncio.Semaphore
) -> None:
    content = (TEST_DATA / case["path"]).read_bytes()
    request = ExtractRequest.model_construct(
        document_id=uuid4(),
        declared_type=case["declaredType"],
        media_type=case["mediaType"],
        content=content,
    )
    tally.documents += 1
    async with limit:
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                response = await pipeline.run(request)
                break
            except ProcessingError as error:
                tally.calls += len(error.runs)
                tally.cost_usd += sum((run.cost_usd or Decimal(0) for run in error.runs), Decimal(0))
                if isinstance(error, ModelUnavailable) and attempt < MAX_ATTEMPTS:
                    await asyncio.sleep(BACKOFF_SECONDS * attempt)
                    continue
                tally.failed_documents += 1
                tally.problems.append(f"FAILED {case['path']}: {error.code} {error}")
                return
    tally.calls += len(response.runs)
    tally.cost_usd += sum((run.cost_usd or Decimal(0) for run in response.runs), Decimal(0))
    score_case(tally, case, response)


def with_overrides(routes: RouteTable, arguments: argparse.Namespace) -> RouteTable:
    tasks = dict(routes.tasks)
    if arguments.extraction_model:
        extraction = tasks[Task.EXTRACTION]
        tasks[Task.EXTRACTION] = extraction.model_copy(update={"primary": arguments.extraction_model})
    if arguments.reread_model:
        tasks[Task.REREAD] = tasks[Task.REREAD].model_copy(update={"primary": arguments.reread_model})
    return RouteTable(tasks=tasks)


def ratio(correct: int, total: int) -> float:
    return correct / total if total else 1.0


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extraction-model")
    parser.add_argument("--reread-model")
    parser.add_argument("--only", help="run only cases whose path contains this text")
    arguments = parser.parse_args()

    cases = json.loads((EVALS / "claim_extraction.json").read_text(encoding="utf-8"))["cases"]
    if arguments.only:
        cases = [case for case in cases if arguments.only in case["path"]]
    routes = with_overrides(load_routes(), arguments)
    tally = Tally()
    limit = asyncio.Semaphore(CONCURRENCY)
    async with model_http_client(get_settings()) as http:
        pipeline = DocumentPipeline(ModelGateway(http, routes, RetentionCatalog(http)))
        await asyncio.gather(*(run_case(pipeline, case, tally, limit) for case in cases))

    critical = ratio(tally.critical_correct, tally.critical_total)
    monetary = ratio(tally.monetary_correct, tally.monetary_total)
    summary = {
        "ranAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "pipelineVersion": PIPELINE_VERSION,
        "routes": {task.value: route.model_dump() for task, route in routes.tasks.items()},
        "documents": tally.documents,
        "failedDocuments": tally.failed_documents,
        "criticalFieldAccuracy": f"{tally.critical_correct}/{tally.critical_total} = {critical:.3f}",
        "monetaryAccuracy": f"{tally.monetary_correct}/{tally.monetary_total} = {monetary:.3f}",
        "otherFieldAccuracy": f"{tally.other_correct}/{tally.other_total}",
        "inventedOnAbsentFields": f"{tally.invented}/{tally.absent_total}",
        "classification": f"{tally.classified}/{tally.documents - tally.failed_documents}",
        "lineItemSets": f"{tally.line_item_correct}/{tally.line_item_cases}",
        "injectedValuesReturned": tally.injected_values,
        "modelCalls": tally.calls,
        "costUsd": str(tally.cost_usd),
        "problems": sorted(tally.problems),
    }
    results = EVALS / "results"
    results.mkdir(exist_ok=True)
    name = (arguments.extraction_model or "default").replace("/", "_")
    (results / f"claim-extraction-{name}.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))

    passed = (
        critical >= TARGETS["critical"]
        and monetary >= TARGETS["monetary"]
        and tally.invented == 0
        and tally.injected_values == 0
        and tally.failed_documents == 0
    )
    print("TARGETS MET" if passed else "TARGETS NOT MET")
    return 0 if passed else 1


if __name__ == "__main__":
    # The selector loop is unnecessary here, as this script opens no database connection.
    sys.exit(asyncio.run(main()))
