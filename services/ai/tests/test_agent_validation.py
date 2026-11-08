"""The rules that decide what of a model's finding stands. No model and no database."""

from uuid import uuid4

import pytest
from pydantic import ValidationError

from prism_ai.agent.schemas import (
    AnalysisOutput,
    AnalysisRequest,
    AppliedValueOutput,
    CitationOutput,
    ClaimFact,
    FindingStatus,
    IssueKind,
    PolicyValue,
    Problem,
    ValueRole,
)
from prism_ai.agent.validate import Evidence, quote_matches, validated
from prism_ai.policies.schemas import ProvisionType

WAITING = Evidence(
    "E1", uuid4(), "3.2", "Specific illnesses", ProvisionType.WAITING_PERIOD,
    "A 24-month waiting period applies to cataract, hernia, and kidney stones.",
)  # fmt: skip
BENEFIT = Evidence(
    "E2", uuid4(), "2.6", "Cataract surgery", ProvisionType.BENEFIT, "Cataract surgery: INR 40,000 per eye."
)
EVIDENCE = {item.evidence_id: item for item in (WAITING, BENEFIT)}
FACTS = {
    "diagnosis": ClaimFact(
        id=uuid4(), name="diagnosis", kind="TEXT", state="PRESENT", value="Cataract, left eye"
    ),
    "admission_date": ClaimFact(id=uuid4(), name="admission_date", kind="DATE", state="CONFLICTING"),
}
VALUES = {
    "V1": PolicyValue(
        id=uuid4(),
        provision_id=WAITING.provision_id,
        name="Waiting period",
        kind="DURATION",
        value="24",
        unit="MONTHS",
    ),
    "V2": PolicyValue(
        id=uuid4(),
        provision_id=BENEFIT.provision_id,
        name="Limit",
        kind="AMOUNT",
        value="40000.00",
        unit="INR",
    ),
}


def output(**changes: object) -> AnalysisOutput:
    base: dict[str, object] = {
        "action": "FINDING",
        "follow_up_queries": [],
        "status": FindingStatus.LIMITED,
        "facts_used": ["diagnosis"],
        "citations": [CitationOutput(evidence_id="E2", quote="INR 40,000 per eye")],
        "reasoning": "The wording caps cataract surgery per eye.",
        "unresolved": [],
        "applied_values": [AppliedValueOutput(value_id="V2", role=ValueRole.AMOUNT_LIMIT)],
    }
    return AnalysisOutput.model_validate(base | changes)


def check(model_output: AnalysisOutput, final_round: bool = False):  # type: ignore[no-untyped-def]
    return validated(
        position=1,
        kind=IssueKind.LIMIT,
        question="Does a sub-limit apply?",
        output=model_output,
        final_round=final_round,
        evidence=EVIDENCE,
        facts=FACTS,
        values=VALUES,
        rounds=1,
        queries=["cataract limit"],
    )


def test_a_finding_with_valid_citations_facts_and_values_stands() -> None:
    finding = check(output())

    assert finding.status == FindingStatus.LIMITED
    assert finding.proposed_status is None
    assert [citation.key for citation in finding.citations] == ["2.6"]
    assert finding.facts_used == [FACTS["diagnosis"].id]
    assert [(value.value_id, value.role) for value in finding.applied_values] == [
        (VALUES["V2"].id, ValueRole.AMOUNT_LIMIT)
    ]
    assert finding.problems == []


def test_a_citation_of_something_not_retrieved_invalidates_the_finding() -> None:
    finding = check(output(citations=[CitationOutput(evidence_id="E9", quote="INR 40,000 per eye")]))

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert finding.proposed_status == FindingStatus.LIMITED
    assert Problem.CITATION_UNKNOWN in finding.problems
    assert finding.applied_values == []


def test_a_quote_not_in_the_cited_provision_invalidates_the_finding() -> None:
    finding = check(output(citations=[CitationOutput(evidence_id="E2", quote="INR 4,00,000 per eye")]))

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert Problem.CITATION_QUOTE_MISMATCH in finding.problems
    assert finding.citations == []


@pytest.mark.parametrize(
    "quote",
    ["“Cataract surgery: INR 40,000 per eye.”", "cataract   surgery: inr 40,000", "INR 40,000 per eye..."],
)
def test_quotes_compare_by_content_not_by_typography(quote: str) -> None:
    assert quote_matches(quote, BENEFIT.text)


def test_a_quote_too_short_to_identify_anything_is_refused() -> None:
    assert not quote_matches("INR", BENEFIT.text)


def test_an_evidenced_status_with_no_citation_becomes_insufficient_evidence() -> None:
    finding = check(output(citations=[], applied_values=[]))

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert Problem.NO_VALID_CITATION in finding.problems


def test_a_finding_resting_on_an_unestablished_fact_becomes_an_unknown() -> None:
    finding = check(output(facts_used=["diagnosis", "admission_date"]))

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert "The admission date is conflicting between documents." in finding.unresolved
    assert Problem.FACT_NOT_ESTABLISHED in finding.problems


def test_an_unknown_fact_name_is_recorded_and_ignored() -> None:
    finding = check(output(facts_used=["patient_age"]))
    assert Problem.FACT_UNKNOWN in finding.problems
    assert finding.facts_used == []


@pytest.mark.parametrize(
    "applied",
    [
        AppliedValueOutput(value_id="V1", role=ValueRole.WAITING_PERIOD),  # its provision is not cited
        AppliedValueOutput(value_id="V2", role=ValueRole.COPAYMENT),  # an amount cannot be a percentage share
        AppliedValueOutput(value_id="V7", role=ValueRole.AMOUNT_LIMIT),  # not a confirmed value
    ],
)
def test_a_value_is_applied_only_from_a_cited_provision_in_a_fitting_role(
    applied: AppliedValueOutput,
) -> None:
    finding = check(output(applied_values=[applied]))

    assert finding.applied_values == []
    assert Problem.VALUE_REJECTED in finding.problems
    assert finding.status == FindingStatus.LIMITED


def test_asking_to_search_again_on_the_last_round_gives_an_unknown() -> None:
    finding = check(output(action="SEARCH_MORE", follow_up_queries=["more"]), final_round=True)

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert Problem.SEARCH_LIMIT_REACHED in finding.problems


def test_a_need_for_interpretation_is_kept_without_applying_values() -> None:
    finding = check(output(status=FindingStatus.NEEDS_HUMAN_INTERPRETATION))
    assert finding.status == FindingStatus.NEEDS_HUMAN_INTERPRETATION
    assert finding.applied_values == []


@pytest.mark.parametrize("name", ["patient_name", "hospital_name", "policy_number", "claimant_name"])
def test_identifying_facts_are_refused_at_the_boundary(name: str) -> None:
    with pytest.raises(ValidationError, match="not needed"):
        AnalysisRequest.model_validate(
            {
                "claimId": str(uuid4()),
                "versionId": str(uuid4()),
                "claimType": "REIMBURSEMENT",
                "currency": "INR",
                "sumInsured": "500000.00",
                "coverStart": "2025-04-01",
                "facts": [
                    {"id": str(uuid4()), "name": name, "kind": "TEXT", "state": "PRESENT", "value": "x"}
                ],
                "lineItems": [],
                "policyValues": [],
            }
        )


def test_only_usable_queries_are_searched() -> None:
    from prism_ai.agent.pipeline import usable_queries

    assert usable_queries(["", " ", "a", "cataract  waiting", "cataract waiting", "hernia"], ["hernia"]) == [
        "cataract waiting"
    ]


def check_kind(kind: IssueKind, model_output: AnalysisOutput, facts: dict[str, ClaimFact]):  # type: ignore[no-untyped-def]
    return validated(
        position=1, kind=kind, question="?", output=model_output, final_round=False, evidence=EVIDENCE,
        facts=facts, values=VALUES, rounds=1, queries=[],
    )  # fmt: skip


def test_a_waiting_period_needs_an_established_admission_date_even_if_unnamed() -> None:
    finding = check_kind(
        IssueKind.WAITING_PERIOD,
        output(
            status=FindingStatus.LIMITED,
            citations=[CitationOutput(evidence_id="E1", quote="A 24-month waiting period")],
            applied_values=[AppliedValueOutput(value_id="V1", role=ValueRole.WAITING_PERIOD)],
        ),
        FACTS,
    )  # fmt: skip

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert "The admission date is conflicting between documents." in finding.unresolved
    assert finding.applied_values == []


def test_cover_needs_to_know_what_was_treated() -> None:
    facts = {
        "admission_date": ClaimFact(
            id=uuid4(), name="admission_date", kind="DATE", state="PRESENT", value="2026-01-01"
        )
    }
    finding = check_kind(IssueKind.COVERAGE, output(status=FindingStatus.SUPPORTED, facts_used=[]), facts)

    assert finding.status == FindingStatus.INSUFFICIENT_EVIDENCE
    assert "The diagnosis is not stated in the documents." in finding.unresolved


def test_either_a_diagnosis_or_a_procedure_establishes_the_treatment() -> None:
    facts = {
        "procedure": ClaimFact(
            id=uuid4(), name="procedure", kind="TEXT", state="PRESENT", value="Cataract surgery"
        )
    }
    finding = check_kind(IssueKind.EXCLUSION, output(status=FindingStatus.LIMITED, facts_used=[]), facts)
    assert finding.status == FindingStatus.LIMITED


@pytest.mark.parametrize(
    ("admission", "stated"),
    [
        ("2026-03-14", "35 months and 13 days (1078 days in all)"),
        ("2023-04-01", "0 months and 0 days (0 days in all)"),
        ("2023-03-31", "admission is before cover began"),
        ("not a date", "unknown"),
    ],
)
def test_time_from_cover_start_is_computed_for_the_model(admission: str, stated: str) -> None:
    from datetime import date

    from prism_ai.agent.pipeline import cover_duration

    assert cover_duration(date(2023, 4, 1), admission) == stated


def test_months_are_added_as_a_calendar_does() -> None:
    from datetime import date

    from prism_ai.agent.pipeline import add_months, cover_duration

    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)
    assert cover_duration(date(2024, 1, 31), "2024-03-01") == "1 months and 1 days (30 days in all)"
