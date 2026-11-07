"""Measures policy extraction against the golden set, using real models. Costs money; run on demand.

    python -m uv run python evals/run_policy_extraction.py [--model <openrouter model>]

Each policy is read in one batch per eight pages, as the ingestion workflow does. Targets are in
docs/testing.md. Joining provisions across batches is core's job, and is tested there.
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

from prism_ai.gateway.client import ModelGateway
from prism_ai.gateway.errors import ProcessingError
from prism_ai.gateway.retention import RetentionCatalog
from prism_ai.gateway.routes import RouteTable, Task, load_routes
from prism_ai.main import model_http_client
from prism_ai.policies.pipeline import MAX_BATCH_PAGES, PIPELINE_VERSION, PolicyPipeline
from prism_ai.policies.schemas import ExtractedProvision, PolicyPagesRequest, ProvisionType, ValueState
from prism_ai.settings import get_settings

EVALS = Path(__file__).parent
TEST_DATA = EVALS.parents[2] / "test-data"
TARGETS = {"critical": 0.98, "provisions": 0.95}


@dataclass
class Tally:
    values_total: int = 0
    values_correct: int = 0
    provisions_total: int = 0
    provisions_correct: int = 0
    exceptions_total: int = 0
    exceptions_found: int = 0
    references_total: int = 0
    references_found: int = 0
    footnotes_total: int = 0
    footnotes_found: int = 0
    unsupported_present: int = 0
    failed: int = 0
    cost_usd: Decimal = Decimal(0)
    problems: list[str] = field(default_factory=list)


def has_value(provision: ExtractedProvision, expected: list[str]) -> bool:
    kind, value, unit = expected
    return any(
        item.state == ValueState.PRESENT and item.kind == kind and item.value == value and item.unit == unit
        for item in provision.values
    )


def score(tally: Tally, case: dict[str, Any], provisions: list[ExtractedProvision]) -> None:
    by_key: dict[str, list[ExtractedProvision]] = {}
    for provision in provisions:
        if provision.key:
            by_key.setdefault(provision.key, []).append(provision)
        # Code only marks a value PRESENT when its quote is on the page; this proves that held.
        tally.unsupported_present += sum(
            1 for item in provision.values if item.state == ValueState.PRESENT and item.region is None
        )
    path = case["path"]
    for key, expected in case["provisions"].items():
        found = by_key.get(key, [])
        tally.provisions_total += 1
        if any(item.type == expected["type"] for item in found):
            tally.provisions_correct += 1
        else:
            types = [item.type for item in found]
            tally.problems.append(f"PROVISION {path} {key}: wanted {expected['type']}, got {types}")
        for value in expected.get("values", []):
            tally.values_total += 1
            if any(has_value(item, value) for item in found):
                tally.values_correct += 1
            else:
                seen = [(v.kind, v.value, v.unit, v.state, v.problem) for item in found for v in item.values]
                tally.problems.append(f"VALUE {path} {key}: wanted {value}, got {seen}")
        if expected.get("exceptions"):
            tally.exceptions_total += 1
            if any(item.exceptions for item in found):
                tally.exceptions_found += 1
            else:
                tally.problems.append(f"EXCEPTION {path} {key}: none captured")
        for target in expected.get("references", []):
            tally.references_total += 1
            stated = (
                ref.target_key == target and ref.stated_in_text for item in found for ref in item.references
            )
            if any(stated):
                tally.references_found += 1
            else:
                tally.problems.append(f"REFERENCE {path} {key} -> {target}: missing")
        if "footnote" in expected:
            tally.footnotes_total += 1
            if any(expected["footnote"].lower() in item.text.lower() for item in found):
                tally.footnotes_found += 1
            else:
                tally.problems.append(f"FOOTNOTE {path} {key}: not attached")

    schedule = [item for item in provisions if item.type == ProvisionType.SCHEDULE]
    for value in case["schedule"]:
        tally.values_total += 1
        if any(has_value(item, value) for item in schedule):
            tally.values_correct += 1
        else:
            tally.problems.append(f"SCHEDULE {path}: wanted {value}")


async def run_case(pipeline: PolicyPipeline, case: dict[str, Any], tally: Tally) -> None:
    content = (TEST_DATA / case["path"]).read_bytes()
    provisions: list[ExtractedProvision] = []
    first = 1
    while True:
        request = PolicyPagesRequest.model_construct(
            document_id=uuid4(), media_type="application/pdf", first_page=first,
            last_page=first + MAX_BATCH_PAGES - 1, content=content,
        )  # fmt: skip
        try:
            response = await pipeline.run(request)
        except ProcessingError as error:
            tally.failed += 1
            tally.problems.append(f"FAILED {case['path']}: {error.code}")
            return
        tally.cost_usd += sum((run.cost_usd or Decimal(0) for run in response.runs), Decimal(0))
        provisions.extend(response.provisions)
        if response.last_page >= response.page_count:
            break
        first = response.last_page + 1
    score(tally, case, provisions)


def ratio(found: int, total: int) -> float:
    return found / total if total else 1.0


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model")
    arguments = parser.parse_args()
    cases = json.loads((EVALS / "policy_extraction.json").read_text(encoding="utf-8"))["cases"]
    routes = load_routes()
    if arguments.model:
        tasks = dict(routes.tasks)
        route = tasks[Task.POLICY_EXTRACTION]
        tasks[Task.POLICY_EXTRACTION] = route.model_copy(update={"primary": arguments.model})
        routes = RouteTable(tasks=tasks)

    tally = Tally()
    async with model_http_client(get_settings()) as http:
        pipeline = PolicyPipeline(ModelGateway(http, routes, RetentionCatalog(http)))
        await asyncio.gather(*(run_case(pipeline, case, tally) for case in cases))

    critical = ratio(tally.values_correct, tally.values_total)
    provisions = ratio(tally.provisions_correct, tally.provisions_total)
    summary = {
        "ranAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "pipelineVersion": PIPELINE_VERSION,
        "model": routes.route(Task.POLICY_EXTRACTION).primary,
        "failedPolicies": tally.failed,
        "criticalValueAccuracy": f"{tally.values_correct}/{tally.values_total} = {critical:.3f}",
        "provisionsFound": f"{tally.provisions_correct}/{tally.provisions_total} = {provisions:.3f}",
        "exceptionsCaptured": f"{tally.exceptions_found}/{tally.exceptions_total}",
        "referencesFound": f"{tally.references_found}/{tally.references_total}",
        "footnotesAttached": f"{tally.footnotes_found}/{tally.footnotes_total}",
        "presentWithoutSource": tally.unsupported_present,
        "costUsd": str(tally.cost_usd),
        "problems": sorted(tally.problems),
    }
    results = EVALS / "results"
    results.mkdir(exist_ok=True)
    name = summary["model"].replace("/", "-")
    (results / f"policy-extraction-{name}.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    passed = (
        critical >= TARGETS["critical"]
        and provisions >= TARGETS["provisions"]
        and tally.failed == 0
        and tally.unsupported_present == 0
    )
    print("TARGETS MET" if passed else "TARGETS NOT MET")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
