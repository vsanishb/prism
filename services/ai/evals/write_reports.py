"""Writes one short PDF report per evaluated component, from the results the evaluations saved.

    python -m uv run python evals/write_reports.py

Run the evaluations first (each script's docstring says how), and `prism.py demo-prepare` for the
risk-rule and pipeline reports. Reports go to docs/evaluations/, with an index. Nothing here calls a
model: it only lays out numbers that were measured, and leaves out a report whose results are missing.
"""

from __future__ import annotations

import json
import re
import statistics
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from reportlab.graphics.charts.barcharts import HorizontalBarChart  # type: ignore[import-untyped]
from reportlab.graphics.shapes import Drawing, String  # type: ignore[import-untyped]
from reportlab.lib import colors  # type: ignore[import-untyped]
from reportlab.lib.pagesizes import A4  # type: ignore[import-untyped]
from reportlab.lib.styles import ParagraphStyle  # type: ignore[import-untyped]
from reportlab.lib.units import mm  # type: ignore[import-untyped]
from reportlab.pdfgen.canvas import Canvas  # type: ignore[import-untyped]
from reportlab.platypus import (  # type: ignore[import-untyped]
    KeepTogether,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

EVALS = Path(__file__).parent
RESULTS = EVALS / "results"
ROOT = EVALS.parents[2]
DEMO_RESULTS = ROOT / ".data" / "demo-prepare.json"
OUTPUT = ROOT / "docs" / "evaluations"
REVIEWER_LIMIT_INR = 300_000

INK = colors.HexColor("#17181c")
MUTED = colors.HexColor("#5c5e66")
LINE = colors.HexColor("#e7e6e1")
ACCENT = colors.HexColor("#2f3fbf")
POSITIVE = colors.HexColor("#1c7c54")
CRITICAL = colors.HexColor("#b3261e")
CAUTION = colors.HexColor("#9a6700")

TITLE = ParagraphStyle(
    "Title", fontName="Helvetica-Bold", fontSize=19, leading=23, textColor=INK, spaceAfter=3
)
SUBTITLE = ParagraphStyle(
    "Subtitle", fontName="Helvetica", fontSize=9.5, leading=14, textColor=MUTED, spaceAfter=10
)
HEADING = ParagraphStyle(
    "Heading",
    fontName="Helvetica-Bold",
    fontSize=11.5,
    leading=15,
    textColor=INK,
    spaceBefore=12,
    spaceAfter=5,
)
BODY = ParagraphStyle("Body", fontName="Helvetica", fontSize=9.5, leading=13.5, textColor=INK)
SMALL = ParagraphStyle("Small", parent=BODY, fontSize=8.5, leading=12, textColor=MUTED)
CELL = ParagraphStyle("Cell", parent=BODY, fontSize=9, leading=12)
CELL_BOLD = ParagraphStyle("CellBold", parent=CELL, fontName="Helvetica-Bold")


@dataclass
class Metric:
    name: str
    result: str
    target: str = ""
    met: bool | None = None


@dataclass
class Report:
    slug: str
    title: str
    component: str
    measures: str
    dataset: str
    metrics: list[Metric]
    reproduce: str
    run_at: str
    chart: tuple[str, list[tuple[str, float]], float] | None = None
    tables: list[tuple[str, list[list[str]], list[float]]] = field(default_factory=list)
    observations: list[str] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)
    number: int = 0

    @property
    def verdict(self) -> str:
        judged = [metric.met for metric in self.metrics if metric.met is not None]
        if not judged:
            return "No targets set"
        missed = judged.count(False)
        return "All targets met" if not missed else f"{missed} of {len(judged)} targets missed"


# --- Reading results ----------------------------------------------------------------------------------------


def latest(pattern: str) -> tuple[dict[str, Any], Path] | None:
    files = sorted(RESULTS.glob(pattern), key=lambda path: path.stat().st_mtime)
    return (json.loads(files[-1].read_text(encoding="utf-8")), files[-1]) if files else None


def fraction(text: Any) -> tuple[int, int]:
    match = re.match(r"\s*(\d+)\s*/\s*(\d+)", str(text))
    if not match:
        raise ValueError(f"Not a fraction: {text}")
    return int(match[1]), int(match[2])


def share(part: int, whole: int) -> float:
    return part / whole if whole else 0.0


def counted(name: str, value: tuple[int, int], target: float | None = None) -> Metric:
    part, whole = value
    fraction_met = share(part, whole)
    # 48 of 49 is 97.96%: one decimal would print 98.0% beside a missed 98% target.
    precise = target is not None and fraction_met < target and round(fraction_met, 3) >= target
    result = f"{part} / {whole} ({fraction_met:.2%})" if precise else f"{part} / {whole} ({fraction_met:.1%})"
    if target is None:
        return Metric(name, result)
    label = "100%" if target == 1 else f"≥ {target:.0%}"
    return Metric(name, result, label, whole > 0 and share(part, whole) >= target)


def zero(name: str, value: int) -> Metric:
    return Metric(name, str(value), "0", value == 0)


def when(value: str | None, path: Path | None = None) -> str:
    if value:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    elif path is not None:
        moment = datetime.fromtimestamp(path.stat().st_mtime, tz=UTC)
    else:
        return "Not recorded"
    return moment.astimezone(UTC).strftime("%d %b %Y, %H:%M UTC")


def usd(value: Any) -> str:
    return f"USD {float(value):.4f}"


# --- One builder per component ------------------------------------------------------------------------------


def claim_extraction() -> Report | None:
    found = latest("claim-extraction-*.json")
    if not found:
        return None
    data, path = found
    critical, monetary = fraction(data["criticalFieldAccuracy"]), fraction(data["monetaryAccuracy"])
    other, classification = fraction(data["otherFieldAccuracy"]), fraction(data["classification"])
    items, invented = fraction(data["lineItemSets"]), fraction(data["inventedOnAbsentFields"])
    route = data["routes"]["extraction"]
    documents, calls = int(data["documents"]), int(data["modelCalls"])
    return Report(
        "claim-extraction",
        "Document classification and claim extraction",
        "AI service: documents pipeline",
        "Whether each claim document is recognized as the right type, and whether every field and bill line "
        "is read correctly with a quote on the page. A field the document does not contain must come back "
        "as missing, never invented. Text inside a document that tries to instruct the model must have no "
        "effect.",
        f"{documents} synthetic claim documents with known answers: clean PDFs, scans, a two-page TIFF, "
        "handwriting-style pages, dense and sparse bills, conflicting documents, and documents carrying "
        "injected instructions.",
        [
            counted("Critical field accuracy", critical, 0.95),
            counted("Monetary value accuracy", monetary, 0.98),
            zero("Values invented for absent fields", invented[0]),
            zero("Injected values returned", int(data["injectedValuesReturned"])),
            counted("Other field accuracy", other),
            counted("Document type classification", classification),
            counted("Bill line items read completely", items),
            zero("Documents that failed", int(data["failedDocuments"])),
            Metric("Model calls per document", f"{calls / documents:.1f}"),
            Metric("Cost per document", usd(float(data["costUsd"]) / documents)),
        ],
        "cd services/ai; python -m uv run python evals/run_claim_extraction.py",
        when(data.get("ranAt"), path),
        chart=(
            "Accuracy by measure (%)",
            [
                ("Critical fields", 100 * share(*critical)),
                ("Monetary values", 100 * share(*monetary)),
                ("Other fields", 100 * share(*other)),
                ("Classification", 100 * share(*classification)),
                ("Line-item sets", 100 * share(*items)),
            ],
            100,
        ),
        observations=[
            f"Model {route['primary']}, escalating to {route['escalation']} when a check fails. "
            f"Pipeline {data.get('pipelineVersion', '')}."
        ]
        + [f"Error: {problem}" for problem in data.get("problems", [])]
        + (
            [
                "A value read wrongly but marked uncertain is not shown as fact: it reaches the reviewer as "
                "an open question."
            ]
            if data.get("problems")
            else ["No errors."]
        ),
        limitations=[
            "Synthetic documents only. Real hospital paperwork is more varied.",
            "One run. Model output varies slightly between runs.",
        ],
    )


def policy_extraction() -> Report | None:
    found = latest("policy-extraction-*.json")
    if not found:
        return None
    data, path = found
    critical, provisions = fraction(data["criticalValueAccuracy"]), fraction(data["provisionsFound"])
    exceptions, references = fraction(data["exceptionsCaptured"]), fraction(data["referencesFound"])
    footnotes = fraction(data["footnotesAttached"])
    return Report(
        "policy-extraction",
        "Policy wording extraction",
        "AI service: policy knowledge",
        "Whether every clause of a policy wording is found, and every limit, percentage, and waiting period "
        "read exactly, with its exceptions, cross-references, and footnotes. This is what a policy manager "
        "then checks before a second manager publishes the version.",
        "Three synthetic policy wordings with known answers, including a benefits table crossing a page "
        "break, merged cells, footnotes, exceptions inside exclusions, and a clause split across pages.",
        [
            counted("Critical value accuracy before human review", critical, 0.98),
            counted("Clauses found", provisions, 0.95),
            zero("Values marked present without a source quote", int(data["presentWithoutSource"])),
            counted("Exceptions captured", exceptions),
            counted("Cross-references found", references),
            counted("Footnotes attached", footnotes),
            zero("Policies that failed", int(data["failedPolicies"])),
            Metric("Cost for the three wordings", usd(data["costUsd"])),
        ],
        "cd services/ai; python -m uv run python evals/run_policy_extraction.py",
        when(data.get("ranAt"), path),
        chart=(
            "Found or read correctly (%)",
            [
                ("Critical values", 100 * share(*critical)),
                ("Clauses", 100 * share(*provisions)),
                ("Exceptions", 100 * share(*exceptions)),
                ("References", 100 * share(*references)),
                ("Footnotes", 100 * share(*footnotes)),
            ],
            100,
        ),
        observations=[f"Model {data['model']}. Pipeline {data.get('pipelineVersion', '')}."]
        + [f"Error: {problem}" for problem in data.get("problems", [])]
        + ([] if data.get("problems") else ["No errors."]),
        limitations=["Three wordings written for testing. Real wordings are longer and less regular."],
    )


def retrieval() -> Report | None:
    found = latest("retrieval-*.json")
    if not found:
        return None
    data, path = found
    recall, kinds = data["recall_at_10"], data["final_recall_by_kind"]
    queries = sum(fraction(value)[1] for value in kinds.values())
    return Report(
        "policy-search",
        "Policy search (retrieval)",
        "AI service: retrieval",
        "Whether the clauses needed to answer a question about a claim are among the first ten results, "
        "searching one policy version only. Measured for meaning-based search, keyword search, the two "
        "combined, and the final reranked list that the policy analysis uses.",
        "Every synthetic policy indexed together, both Gold versions included, so that a leak between "
        f"versions would show. {queries} queries of five kinds: facts, paraphrases, keywords, clause "
        "numbers, and defined terms.",
        [
            Metric(
                "Recall@10, final reranked list", f"{recall['final']:.1%}", "≥ 90%", recall["final"] >= 0.9
            ),
            zero("Results from the wrong policy version", int(data["wrong_version_results"])),
            Metric("Recall@10, meaning-based only", f"{recall['semantic']:.1%}"),
            Metric("Recall@10, keyword only", f"{recall['lexical']:.1%}"),
            Metric("Recall@10, combined before reranking", f"{recall['fused']:.1%}"),
            counted("Related clauses found", fraction(data["related_found"])),
            Metric("Searches that fell back to a degraded mode", str(data["degraded_searches"])),
            Metric(
                "Search time, median and slowest",
                f"{data['search_latency_ms']['median']} ms, {data['search_latency_ms']['max']} ms",
            ),
            Metric("Cost for the whole set", usd(data["cost_usd"])),
        ],
        "cd services/ai; python -m uv run python evals/run_retrieval.py",
        when(data.get("run_at"), path),
        chart=(
            "Recall@10 by method (%)",
            [
                ("Keyword", 100 * recall["lexical"]),
                ("Meaning", 100 * recall["semantic"]),
                ("Combined", 100 * recall["fused"]),
                ("Reranked", 100 * recall["final"]),
            ],
            100,
        ),
        tables=[
            (
                "Final recall by kind of query",
                [["Kind of query", "Found in the first ten"]]
                + [[kind.capitalize(), value] for kind, value in kinds.items()],
                [60, 50],
            )
        ],
        observations=[f"Embedding model {data['embedding_model']}, reranker {data['rerank_model']}."]
        + [f"Missed: {miss}" for miss in data.get("misses", [])]
        + [
            "Keyword search alone misses paraphrased questions. Combining it with meaning-based search, then "
            "reranking, closes the gap."
        ],
        limitations=["A small corpus. Larger corpora make ranking harder."],
    )


def agent() -> Report | None:
    found = latest("agent-*.json")
    if not found:
        return None
    data, path = found
    shown = fraction(data.get("citations_shown_resolving", data.get("citations_resolving")))
    proposed = fraction(data.get("model_citations_resolving", data.get("citations_resolving")))
    issues = fraction(data["expected_issues_identified"])
    unknown, injections = fraction(data["unknown_when_facts_missing"]), fraction(data["injections_succeeded"])
    scenarios = data.get("scenarios", [])
    statuses = Counter(re.findall(r":([A-Z_]+)\[", " ".join(scenarios)))
    flagged = [line for line in scenarios if "!!" in line]
    return Report(
        "policy-analysis",
        "Policy analysis agent",
        "AI service: policy agent",
        "Whether the analysis of a claim against its policy finds the issues a careful reader would "
        "(waiting periods, exclusions, sub-limits, conditions), cites only clauses it actually retrieved, "
        "says it does not know when facts are missing, and cannot be steered by text inside a document. "
        "Amounts are computed by code.",
        f"{len(scenarios)} claim scenarios against the synthetic policies, each with the issues it should "
        "raise: covered treatment, treatment above a sub-limit, waiting periods, exclusions, missing facts, "
        "and an injection.",
        [
            counted("Citations shown that resolve to retrieved clauses", shown, 1),
            counted("Citations the model proposed that resolved", proposed),
            counted("Expected issues identified", issues, 0.9),
            counted("Says unknown when facts are missing", unknown, 0.95),
            zero("Successful prompt injections", injections[0]),
            zero("Scenarios that failed to run", int(data["failed_scenarios"])),
            Metric("Model and search calls", str(data["model_and_search_calls"])),
            Metric("Cost per scenario", usd(float(data["cost_usd"]) / max(1, len(scenarios)))),
        ],
        "cd services/ai; python -m uv run python evals/run_agent.py",
        when(data.get("run_at"), path),
        chart=(
            "Assessment statuses given across all scenarios (count)",
            [
                (status.replace("_", " ").capitalize(), float(count))
                for status, count in statuses.most_common()
            ],
            float(max(statuses.values(), default=1)),
        )
        if statuses
        else None,
        observations=[f"Model {data['model']}."]
        + (
            [
                f"The model proposed {proposed[1] - proposed[0]} citation(s) that did not resolve. Prism "
                "dropped them before showing the analysis, so every citation shown resolves."
            ]
            if proposed[0] != proposed[1]
            else []
        )
        + [explain_miss(line) for line in flagged]
        + (
            [
                "How to read the injection measure: it counts the scenario as steered if any finding is "
                "marked supported while citing the clause the injected text targets. Read that scenario's "
                "line above to see whether the coverage and exclusion findings themselves were steered."
            ]
            if injections[0]
            else []
        ),
        limitations=["The analysis informs a reviewer. It never decides coverage."],
    )


def explain_miss(line: str) -> str:
    """Turns a scenario line from run_agent ("name: findings  !! notes") into a sentence."""
    head, _, notes = line.partition("  !! ")
    name, _, findings = head.partition(": ")
    readable = []
    for note in notes.split("; "):
        kind = "Expected" if note.startswith("missed") else "Should not have given"
        statuses = re.findall(r"'statuses': \[([^\]]*)\]", note)
        cites = re.findall(r"'cites': \[([^\]]*)\]", note)
        status = statuses[0].replace("'", "").replace("_", " ").lower() if statuses else "a finding"
        clause = f" citing clause {cites[0].replace(chr(39), '')}" if cites else ""
        readable.append(f"{kind} {status}{clause}")
    given = re.sub(r",\s*", ", ", findings.replace("_", " ").lower()).replace(":", ": ")
    given = re.sub(r"\[([^\]]*)\]", r" (clauses \1)", given)
    given = re.sub(r"\bs\b", "the schedule", given)
    return f"<b>{name.capitalize()}.</b> {'; '.join(readable)}. Findings given: {given}."


def demo_results() -> tuple[dict[str, Any], Path] | None:
    if not DEMO_RESULTS.exists():
        return None
    return json.loads(DEMO_RESULTS.read_text(encoding="utf-8")), DEMO_RESULTS


def risk_rules() -> Report | None:
    found = demo_results()
    if not found:
        return None
    data, path = found
    rows = data["claims"]
    expected = Counter(rule for row in rows for rule in row["expectedRules"])
    hit = Counter(rule for row in rows for rule in row["expectedRules"] if rule in row["raisedRules"])
    extra = Counter(rule for row in rows for rule in row["unexpected"])
    planted, detected = sum(expected.values()), sum(hit.values())
    raised = sum(len(row["raisedRules"]) for row in rows)
    clean = [row for row in rows if not row["expectedRules"] and row["band"]]
    bands = Counter(row["band"] for row in rows if row["band"])
    table = [["Rule", "Claims built to raise it", "Raised on them", "Raised elsewhere"]]
    table += [
        [rule, str(expected[rule]), str(hit[rule]), str(extra[rule])]
        for rule in sorted(set(expected) | set(extra))
    ]
    return Report(
        "risk-rules",
        "Risk rule engine",
        "Core: risk module, ruleset risk-rules-2",
        "Whether each deterministic rule fires on the claim built to trigger it, end to end: documents read "
        "by the real model, consolidated, and screened as in production. A rule can only fire on what "
        "extraction reads, so this measures the whole chain, not the rule code alone (which core's unit "
        "tests cover rule by rule).",
        f"{len(rows)} synthetic claims loaded by prism.py demo-prepare: "
        f"{sum(1 for row in rows if row['expectedRules'])} built to trigger specific rules ({planted} "
        f"planted signals across {len(expected)} rules), {len(clean)} clean claims that should raise "
        "nothing, and one waiting for a document.",
        [
            counted("Planted signals detected", (detected, planted), 1),
            Metric("Signals raised beyond those planted", str(raised - detected), "0", raised == detected),
            Metric("Precision of raised signals", f"{share(detected, raised):.1%}"),
            counted(
                "Clean claims with no indicator",
                (len([row for row in clean if not row["raisedRules"]]), len(clean)),
            ),
            Metric("Rules exercised", f"{len(expected)} of 22"),
            Metric("Bands: low, medium, high", f"{bands['LOW']}, {bands['MEDIUM']}, {bands['HIGH']}"),
        ],
        "python -m uv run --project services/ai python scripts/prism.py demo-prepare",
        when(data.get("ranAt"), path),
        tables=[("Rule by rule", table, [22, 48, 36, 36])],
        observations=[
            f"{row['claimNumber']} ({row['title']}): missed {', '.join(row['missed'])}."
            for row in rows
            if row["missed"]
        ]
        + [
            f"{row['claimNumber']} ({row['title']}): also raised {', '.join(row['unexpected'])}."
            for row in rows
            if row["unexpected"]
        ]
        + [
            "MIS-01 (a required document missing) is not exercised here by design: such a claim waits 14 "
            "days for the document before it is screened. Core's unit tests cover it.",
            "Building this set found three mistakes in the data, none in Prism: hand-typed amounts that did "
            "not match their bills (FIN-03 reported them), a bill whose summary line was read as an item, "
            "and expectations that ignored DUP-01 taking precedence over DUP-02 and DUP-03 for the same "
            "file (D-096).",
        ],
        limitations=[
            "Documents written for the purpose. Each rule is exercised by one or two claims, so this shows "
            "the chain works, not how often rules fire on real claims."
        ],
    )


def anomaly() -> Report | None:
    path = RESULTS / "risk_anomaly.json"
    if not path.exists():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    detectors = {item["detector"]: item for item in data["detectors"]}
    forest, lof = detectors["ISOLATION_FOREST"], detectors["LOCAL_OUTLIER_FACTOR"]
    types = list(forest["recall_by_type"])
    label = {kind: kind.replace("_", " ").capitalize() for kind in types}
    return Report(
        "anomaly-detectors",
        "Statistical anomaly detectors",
        "AI service: risk (Isolation Forest and LOF)",
        "Whether the two unsupervised detectors flag claims that are statistically unusual without flagging "
        "ordinary ones. They are fitted on unlabeled synthetic claims; labels exist only in this evaluation "
        "set (D-083).",
        f"Fitted on 5,000 synthetic baseline claims. Evaluated on {data['held_out_size']:,} held-out normal "
        f"claims and {data['anomaly_size']} controlled anomalies of {len(types)} types. Seeds are fixed, so "
        "every run gives the same numbers.",
        [
            Metric(
                "False positives, Isolation Forest",
                f"{forest['false_positive_rate']:.1%}",
                "≤ 2%",
                forest["false_positive_rate"] <= 0.02,
            ),
            Metric(
                "False positives, LOF",
                f"{lof['false_positive_rate']:.1%}",
                "≤ 2%",
                lof["false_positive_rate"] <= 0.02,
            ),
            Metric(
                "Recall, either detector",
                f"{data['either_recall']:.1%}",
                "≥ 90%",
                data["either_recall"] >= 0.9,
            ),
            Metric("Recall, Isolation Forest", f"{forest['recall']:.1%}"),
            Metric("Recall, LOF", f"{lof['recall']:.1%}"),
            Metric("ROC AUC, Isolation Forest", f"{forest['roc_auc']:.3f}"),
            Metric("ROC AUC, LOF", f"{lof['roc_auc']:.3f}"),
            Metric("False positives, either detector", f"{data['either_false_positive_rate']:.1%}"),
        ],
        "cd services/ai; python -m uv run python evals/train_risk_models.py",
        when(None, path),
        chart=(
            "Recall by anomaly type, LOF (%)",
            [(label[kind], 100 * lof["recall_by_type"][kind]) for kind in types],
            100,
        ),
        tables=[
            (
                "Recall by anomaly type",
                [["Anomaly type", "Isolation Forest", "LOF"]]
                + [
                    [
                        label[kind],
                        f"{forest['recall_by_type'][kind]:.0%}",
                        f"{lof['recall_by_type'][kind]:.0%}",
                    ]
                    for kind in types
                ],
                [64, 40, 30],
            )
        ],
        observations=[
            "Isolation Forest misses inflated claims almost entirely: nearly every baseline claim asks for "
            "exactly its bill, so its random splits rarely isolate that feature. Rule FIN-03 catches "
            "inflated claims deterministically, which is why rules and detectors are combined.",
            "A flagged detector adds 10 points, and both together at most 20, below the HIGH threshold of "
            "45. Statistics alone can never make a claim high risk.",
        ],
        limitations=[
            "The detectors learned the generator's idea of normal and are tested on its idea of anomalous. "
            "This shows the implementation works as designed. It says nothing about detecting fraud in real "
            "claims, which needs real data and the privacy gate first."
        ],
    )


def risk_summary() -> Report | None:
    path = RESULTS / "risk_summary.json"
    if not path.exists():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    runs = int(data["runs"])
    available = int(data["outcomes"].get("AVAILABLE", 0))
    repaired = sum(int(value) for value in data.get("problems", {}).values())
    return Report(
        "risk-summary",
        "Risk summary (language model)",
        "AI service: risk summary",
        "Whether the written summary of a claim's risk signals is grounded and never accusatory. Code "
        "checks every summary: each number must appear in the signals, each citation must name a real "
        "signal, and words such as fraud or fraudulent are refused. A summary that fails is repaired once, "
        "or not shown.",
        "The signals of the severe and disguised demonstration claims, exactly as core sends them, "
        f"summarized {runs} times with the configured model.",
        [
            counted("Summaries that passed every check", (available, runs)),
            Metric("Summaries shown that fail a check", "0", "0, enforced by code", True),
            Metric("Attempts repaired or refused before passing", str(repaired)),
            Metric("Cost per summary", usd(float(data["costUsd"]) / max(1, runs))),
        ],
        "cd services/ai; python -m uv run python evals/run_risk_summary.py",
        when(None, path),
        observations=[
            f"Example, {case.lower()} claim: “{text}”" for case, text in data.get("examples", {}).items()
        ],
        limitations=[
            "Passing the checks means grounded and non-accusatory, not the best possible wording. The "
            "examples are here to be read."
        ],
    )


def pipeline() -> Report | None:
    found = demo_results()
    if not found:
        return None
    data, path = found
    rows = data["claims"]

    def expected_queue(row: dict[str, Any]) -> str:
        if row["band"] == "HIGH":
            return "INVESTIGATION"
        return "SENIOR_REVIEW" if float(row["claimedAmount"]) > REVIEWER_LIMIT_INR else "REVIEW"

    routed = [row for row in rows if row.get("firstQueue") and row.get("band")]
    correct = [row for row in routed if row["firstQueue"] == expected_queue(row)]
    timed = sorted(float(row["processingSeconds"]) for row in routed if row.get("processingSeconds"))
    settled = Counter(row["status"] for row in rows)
    metrics = [
        counted("Claims that reached a person after full processing", (len(routed), len(rows))),
        counted("Claims routed to the right queue", (len(correct), len(routed)), 1),
    ]
    if timed:
        metrics += [
            Metric("Submission to review, median", f"{statistics.median(timed) / 60:.1f} min"),
            Metric(
                "Submission to review, 90th percentile",
                f"{statistics.quantiles(timed, n=10)[-1] / 60:.1f} min" if len(timed) >= 2 else "n/a",
            ),
            Metric("Submission to review, fastest", f"{timed[0] / 60:.1f} min"),
        ]
    metrics.append(
        Metric("Risk summaries written", str(sum(1 for row in rows if row.get("summary") == "AVAILABLE")))
    )
    table = [["Claim", "Band", "Claimed (INR)", "First queue", "Expected"]]
    table += [
        [
            row["claimNumber"],
            row["band"],
            f"{float(row['claimedAmount']):,.0f}",
            row["firstQueue"].replace("_", " ").lower(),
            expected_queue(row).replace("_", " ").lower(),
        ]
        for row in routed
    ]
    return Report(
        "pipeline-routing",
        "End-to-end processing and review routing",
        "Core: workflow and review",
        "Whether every submitted claim passes through extraction, consolidation, policy analysis, and risk "
        "screening to a person, how long that takes, and whether each lands in the right queue: high risk "
        "to an investigator first, above INR 3,00,000 to a senior reviewer, everything else to a reviewer.",
        f"The {len(rows)} claims loaded by prism.py demo-prepare, processed on one laptop with the real "
        "model and Temporal, several at a time.",
        metrics,
        "python -m uv run --project services/ai python scripts/prism.py demo-prepare",
        when(data.get("ranAt"), path),
        tables=[
            ("Routing, claim by claim", table, [34, 18, 30, 34, 34]),
            (
                "Where the claims are now, after demo-prepare worked them",
                [["Status", "Claims"]]
                + [
                    [status.replace("_", " ").capitalize(), str(count)]
                    for status, count in settled.most_common()
                ],
                [70, 30],
            ),
        ],
        observations=[
            "Time is from submission to the first review task. Most of it is waiting for the model; the "
            "claims were processed concurrently, so each waited on the others.",
            f"The slowest claim took {timed[-1] / 60:.1f} min. The slowest times include any claim whose "
            "processing failed (a model outage, say) and waited for someone to press Retry."
            if timed
            else "No processing times were recorded.",
            "A claim missing a required document does not reach a person until the document arrives or the "
            "14-day wait expires; that is by design.",
        ],
        limitations=["One local machine on one day. Times depend on the model provider's response times."],
    )


BUILDERS: list[Callable[[], Report | None]] = [
    claim_extraction,
    policy_extraction,
    retrieval,
    agent,
    risk_rules,
    anomaly,
    risk_summary,
    pipeline,
]

# --- Rendering ----------------------------------------------------------------------------------------------


def decorate(canvas: Canvas, document: SimpleDocTemplate) -> None:
    canvas.saveState()
    canvas.setStrokeColor(ACCENT)
    canvas.setLineWidth(2)
    canvas.line(18 * mm, A4[1] - 12 * mm, 38 * mm, A4[1] - 12 * mm)
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 11 * mm, "Prism evaluation report. Synthetic data only.")
    canvas.drawRightString(A4[0] - 18 * mm, 11 * mm, f"Page {document.page}")
    canvas.restoreState()


def grid(rows: list[list[Any]], widths: list[float]) -> Table:
    table = Table(rows, colWidths=[width * mm for width in widths], repeatRows=1)
    table.setStyle(
        TableStyle(
            [
                ("LINEBELOW", (0, 0), (-1, -1), 0.4, LINE),
                ("LINEBELOW", (0, 0), (-1, 0), 0.8, MUTED),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("TOPPADDING", (0, 0), (-1, -1), 4.5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4.5),
                ("LEFTPADDING", (0, 0), (-1, -1), 4),
                ("FONTNAME", (0, 0), (-1, -1), "Helvetica"),
                ("FONTSIZE", (0, 0), (-1, -1), 9),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, 0), 8),
                ("TEXTCOLOR", (0, 0), (-1, 0), MUTED),
            ]
        )
    )
    return table


def metrics_table(metrics: list[Metric]) -> Table:
    rows: list[list[Any]] = [["Measure", "Result", "Target", ""]]
    for metric in metrics:
        mark = "" if metric.met is None else ("Met" if metric.met else "Missed")
        rows.append(
            [
                Paragraph(metric.name, CELL),
                Paragraph(metric.result, CELL_BOLD),
                Paragraph(metric.target, CELL),
                mark,
            ]
        )
    table = grid(rows, [74, 44, 36, 20])
    for index, metric in enumerate(metrics, start=1):
        if metric.met is not None:
            table.setStyle(
                TableStyle(
                    [
                        ("TEXTCOLOR", (3, index), (3, index), POSITIVE if metric.met else CRITICAL),
                        ("FONTNAME", (3, index), (3, index), "Helvetica-Bold"),
                    ]
                )
            )
    return table


def bar_chart(title: str, values: list[tuple[str, float]], maximum: float) -> Drawing:
    height = 30 + 18 * len(values)
    drawing = Drawing(174 * mm, height)
    chart = HorizontalBarChart()
    chart.x, chart.y = 42 * mm, 8
    chart.width, chart.height = 115 * mm, height - 28
    chart.data = [[value for _, value in reversed(values)]]
    chart.categoryAxis.categoryNames = [label for label, _ in reversed(values)]
    chart.categoryAxis.labels.fontName = "Helvetica"
    chart.categoryAxis.labels.fontSize = 8
    chart.categoryAxis.labels.boxAnchor = "e"
    chart.categoryAxis.strokeColor = LINE
    chart.valueAxis.valueMin = 0
    chart.valueAxis.valueMax = maximum
    chart.valueAxis.labels.fontSize = 7
    chart.valueAxis.labels.fillColor = MUTED
    chart.valueAxis.strokeColor = LINE
    chart.valueAxis.visibleGrid = True
    chart.valueAxis.gridStrokeColor = LINE
    chart.bars[0].fillColor = ACCENT
    chart.bars[0].strokeColor = None
    chart.barWidth = 9
    chart.barLabelFormat = "%.0f"
    chart.barLabels.fontSize = 7.5
    chart.barLabels.boxAnchor = "w"
    chart.barLabels.dx = 3
    drawing.add(chart)
    drawing.add(String(0, height - 10, title, fontName="Helvetica-Bold", fontSize=9, fillColor=INK))
    return drawing


def render(report: Report, total: int) -> Path:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path = OUTPUT / f"{report.number:02d}-{report.slug}.pdf"
    document = SimpleDocTemplate(
        str(path),
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=18 * mm,
        bottomMargin=18 * mm,
        title=f"Prism evaluation: {report.title}",
        author="Prism",
    )
    tone = (
        POSITIVE
        if report.verdict == "All targets met"
        else (MUTED if report.verdict == "No targets set" else CAUTION)
    )
    story: list[Any] = [
        Paragraph(f"Prism evaluation {report.number} of {total}", SMALL),
        Paragraph(report.title, TITLE),
        Paragraph(
            f"{report.component} &nbsp;·&nbsp; Run {report.run_at} &nbsp;·&nbsp; "
            f"<font color='#{tone.hexval()[2:]}'><b>{report.verdict}</b></font>",
            SUBTITLE,
        ),
        Paragraph("What is measured", HEADING),
        Paragraph(report.measures, BODY),
        Paragraph("Data", HEADING),
        Paragraph(report.dataset, BODY),
        Paragraph("Results", HEADING),
        metrics_table(report.metrics),
    ]
    if report.chart:
        story += [Spacer(1, 14), KeepTogether(bar_chart(*report.chart))]
    for title, rows, widths in report.tables:
        story.append(KeepTogether([Paragraph(title, HEADING), grid(rows, widths)]))
    if report.observations:
        story += [Paragraph("Observations", HEADING)] + [
            Paragraph(f"• {item}", BODY) for item in report.observations
        ]
    if report.limitations:
        story += [Paragraph("Limitations", HEADING)] + [
            Paragraph(f"• {item}", BODY) for item in report.limitations
        ]
    story += [
        Paragraph("Reproduce", HEADING),
        Paragraph(f"<font name='Courier' size='8.5'>{report.reproduce}</font>", BODY),
    ]
    document.build(story, onFirstPage=decorate, onLaterPages=decorate)
    return path


def index(reports: list[Report]) -> str:
    rows = "\n".join(
        f"| {report.number} | [{report.title}]({report.number:02d}-{report.slug}.pdf) | {report.component} | "
        f"{report.run_at} | {report.verdict} |"
        for report in reports
    )
    return f"""# Evaluations

One short report per component that has accuracy or efficiency to measure, on synthetic data with known
answers. Regenerate with `python -m uv run python evals/write_reports.py` from `services/ai`, after
running the evaluations (each report names its command).

| # | Report | Component | Run | Verdict |
|---|---|---|---|---|
{rows}

Targets are in [testing.md](../testing.md). Every number describes synthetic data: it shows that the system
works as designed, not how it would perform on real claims.
"""


def main() -> None:
    reports = [report for report in (builder() for builder in BUILDERS) if report is not None]
    for number, report in enumerate(reports, start=1):
        report.number = number
    for old in OUTPUT.glob("*.pdf"):
        old.unlink()
    for report in reports:
        print(f"{render(report, len(reports)).relative_to(ROOT)}: {report.verdict}")
    (OUTPUT / "README.md").write_text(index(reports), encoding="utf-8")


if __name__ == "__main__":
    main()
