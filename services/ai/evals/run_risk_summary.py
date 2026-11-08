"""Measures the risk summary (D-086) with the real configured model. Costs a fraction of a cent a run.

    python -m uv run python evals/run_risk_summary.py [--runs 5]

The cases are the severe and disguised demonstrations' signals, exactly as core stored and sent them.
It reports how often a summary passed Prism's checks, which checks refused the others, and prints one
summary per case for reading. Passing the checks means grounded and non-accusatory. It does not mean
the summary is the best way to say it: read them.
"""

import argparse
import asyncio
import json
import sys
from collections import Counter
from pathlib import Path
from uuid import uuid4

EVALS = Path(__file__).parent
sys.path.insert(0, str(EVALS.parent))

from prism_ai.gateway.client import ModelGateway  # noqa: E402
from prism_ai.gateway.retention import RetentionCatalog  # noqa: E402
from prism_ai.gateway.routes import load_routes  # noqa: E402
from prism_ai.main import model_http_client  # noqa: E402
from prism_ai.risk.schemas import ExplanationRequest, ExplanationStatus  # noqa: E402
from prism_ai.risk.summary import RiskSummarizer  # noqa: E402
from prism_ai.settings import get_settings  # noqa: E402

CASES = EVALS / "risk_summary_cases.json"
RESULTS = EVALS / "results" / "risk_summary.json"


async def evaluate(runs: int) -> dict[str, object]:
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    outcomes: Counter[str] = Counter()
    problems: Counter[str] = Counter()
    examples: dict[str, str] = {}
    cost = 0.0
    async with model_http_client(get_settings()) as http:
        summarizer = RiskSummarizer(ModelGateway(http, load_routes(), RetentionCatalog(http)))
        for case in cases:
            for _ in range(runs):
                request = ExplanationRequest.model_validate(case["request"] | {"claimId": str(uuid4())})
                response = await summarizer.summarize(request)
                outcomes[response.status.value] += 1
                cost += sum(float(run.cost_usd or 0) for run in response.runs)
                repairs = sum(1 for run in response.runs if run.outcome.value == "INVALID_OUTPUT")
                if repairs:
                    problems["repaired or refused attempts"] += repairs
                if response.status is ExplanationStatus.AVAILABLE and response.text:
                    examples.setdefault(case["name"], response.text)
                elif response.problem:
                    problems[response.problem] += 1
    return {
        "runs": runs * len(cases),
        "outcomes": dict(outcomes),
        "problems": dict(problems),
        "costUsd": round(cost, 5),
        "examples": examples,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=5)
    result = asyncio.run(evaluate(parser.parse_args().runs))
    print(json.dumps(result, indent=2))
    RESULTS.parent.mkdir(exist_ok=True)
    RESULTS.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
