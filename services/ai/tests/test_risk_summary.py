"""The AI summary of a risk screening (D-086): code decides whether it may be shown, it never changes
the screening, and the screening stands when it is unavailable."""

import asyncio
import json
from typing import Any
from uuid import uuid4

import httpx
import pytest

from prism_ai.risk.schemas import (
    ExplanationRequest,
    ExplanationResponse,
    ExplanationStatus,
    Signal,
    SignalKind,
    SignalSource,
    SummaryOutput,
)
from prism_ai.risk.summary import RiskSummarizer, checked
from tests.support.scripted import ScriptedProvider, completion

REQUEST = ExplanationRequest(
    claim_id=uuid4(),
    band="HIGH",
    score=57,
    route="SENIOR_REVIEW",
    signals=[
        Signal(
            id="S1",
            source=SignalSource.RULE,
            kind=SignalKind.FRAUD_INDICATOR,
            category="DUPLICATE",
            severity="HIGH",
            title="A bill closely matches a bill in another claim",
            explanation="5 of 7 line items match a bill in another claim from the same hospital.",
            contribution=25,
        ),
        Signal(
            id="S2",
            source=SignalSource.ISOLATION_FOREST,
            kind=SignalKind.STATISTICAL_ANOMALY,
            category="STATISTICAL",
            severity=None,
            title="Statistically unusual compared with all baseline claims",
            explanation="More unusual than 99.84% of baseline claims.",
            contribution=10,
        ),
    ],
)
GOOD = {
    "summary": (
        "The bill closely matches a bill in another claim from the same hospital, with 5 of 7 line items "
        "the same [S1]. The claim is also statistically unusual compared with the baseline [S2]. It goes "
        "to senior review because of these signals, and the reviewer decides."
    ),
    "signals_cited": ["S1", "S2"],
}


def output(**changes: Any) -> SummaryOutput:
    return SummaryOutput.model_validate(GOOD | changes)


def test_a_grounded_summary_is_kept_with_its_citations() -> None:
    text, cited = checked(output(), REQUEST)
    assert cited == ["S1", "S2"]
    assert text.startswith("The bill closely matches")


@pytest.mark.parametrize(
    ("summary", "problem"),
    [
        ("This claim is fraudulent [S1].", "fraudulent"),
        ("Fraud has been confirmed by matching bills [S1].", "Fraud has been confirmed"),
        ("The patient committed fraud [S1].", "committed fraud"),
        ("The claim should be rejected [S1].", "rejected"),
        ("We recommend the claim be denied [S1].", "denied"),
        ("The claim can be approved after checks [S1].", "approved"),
    ],
)
def test_accusations_and_decisions_are_refused(summary: str, problem: str) -> None:
    with pytest.raises(ValueError, match=problem):
        checked(output(summary=summary), REQUEST)


def test_a_signal_that_was_not_given_cannot_be_cited() -> None:
    with pytest.raises(ValueError, match="S7"):
        checked(output(summary="Matching bills [S1] and an unusual stay [S7]."), REQUEST)


def test_a_summary_must_cite_its_signals() -> None:
    with pytest.raises(ValueError, match="cite"):
        checked(output(summary="The bill matches another claim's bill.", signals_cited=[]), REQUEST)


def test_numbers_must_come_from_the_signals() -> None:
    with pytest.raises(ValueError, match="240000"):
        checked(output(summary="The bill of 2,40,000 matches another claim [S1]."), REQUEST)
    # Numbers given in the signals, and the score, may be repeated.
    checked(output(summary="5 of 7 items match [S1], and it scored 57 [S2]."), REQUEST)


def test_an_overlong_summary_is_refused() -> None:
    with pytest.raises(ValueError, match="longer"):
        checked(output(summary="Matching bills [S1]. " * 60), REQUEST)


def summarize(provider: ScriptedProvider) -> ExplanationResponse:
    async def body() -> ExplanationResponse:
        async with provider.client() as http:
            return await RiskSummarizer(provider.gateway(http)).summarize(REQUEST)

    return asyncio.run(body())


def test_an_accusing_summary_is_repaired_once_and_then_kept() -> None:
    provider = ScriptedProvider(
        [completion(GOOD | {"summary": "This is fraudulent [S1]."}), completion(GOOD)]
    )
    response = summarize(provider)
    assert response.status is ExplanationStatus.AVAILABLE
    assert response.cited == ["S1", "S2"]
    assert [run.outcome.value for run in response.runs] == ["INVALID_OUTPUT", "SUCCEEDED"]
    # The repair is told why the first answer could not be shown.
    assert "fraudulent" in json.dumps(provider.bodies[1]["messages"])


def test_a_summary_that_never_passes_is_reported_unavailable_with_usage() -> None:
    bad = completion(GOOD | {"summary": "The claim should be rejected [S1]."})
    response = summarize(ScriptedProvider([bad, bad, bad]))
    assert response.status is ExplanationStatus.UNAVAILABLE
    assert response.text is None
    assert response.problem == "model_output_invalid"
    assert len(response.runs) == 3


def test_an_unavailable_model_leaves_no_summary_and_raises_nothing() -> None:
    limited = httpx.Response(429, json={"error": {"code": 429}})
    response = summarize(ScriptedProvider([limited, limited]))
    assert response.status is ExplanationStatus.UNAVAILABLE
    assert response.problem == "model_unavailable"


def test_only_the_configured_model_and_de_identified_signals_are_used() -> None:
    provider = ScriptedProvider([completion(GOOD)])
    summarize(provider)
    body = provider.bodies[0]
    assert body["model"] == "deepseek/deepseek-v4.1-flash-1017"
    assert body["provider"] == {"zdr": True, "data_collection": "deny"}
    sent = json.dumps(body["messages"])
    assert "<screening>" in sent
    assert "[S1] RULE, FRAUD_INDICATOR, DUPLICATE" in sent


def test_the_summary_carries_no_score_band_or_route() -> None:
    """Core stores the summary apart from the screening. The contract leaves nothing it could overwrite."""
    assert not {"band", "score", "route", "contribution"} & set(ExplanationResponse.model_fields)
