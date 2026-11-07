"""Policy values become trusted only when the page proves them. Scripted models, real synthetic PDFs."""

import asyncio
from pathlib import Path
from typing import Any
from uuid import uuid4

import pytest

from prism_ai.documents.schemas import Grounding
from prism_ai.gateway.errors import DocumentUnreadable
from prism_ai.policies.pipeline import (
    MAX_BATCH_PAGES,
    PolicyPipeline,
    duration_from_quote,
    normalize_value,
)
from prism_ai.policies.schemas import (
    PolicyPagesRequest,
    PolicyPagesResponse,
    PolicyValueKind,
    ProvisionType,
    RelationType,
    ValueProblem,
    ValueState,
)
from tests.support.scripted import ScriptedProvider, completion

GOLD = Path(__file__).parents[3] / "test-data/policies/family-floater-gold-v1.pdf"


def value(raw: str, quote: str, kind: str = "AMOUNT", page: int = 1) -> dict[str, Any]:
    return {"name": "Limit", "raw": raw, "kind": kind, "qualifier": "", "page": page, "quote": quote}


def provision(**overrides: Any) -> dict[str, Any]:
    base = {
        "key": "2.6",
        "type": "BENEFIT",
        "title": "Cataract surgery",
        "text": "2.6 Cataract surgery INR 40,000 per eye See waiting period in Clause 3.2",
        "page": 1,
        "quote": "Cataract surgery INR 40,000 per eye",
        "parent_key": "2",
        "exceptions": [],
        "references": [{"target_key": "3.2", "relation": "QUALIFIES"}],
        "values": [value("INR 40,000", "Cataract surgery INR 40,000 per eye")],
        "continues_on_next_page": False,
        "continued_from_previous_page": False,
    }
    return base | overrides


def run(provisions: list[dict[str, Any]], first: int = 1, last: int = 2) -> PolicyPagesResponse:
    provider = ScriptedProvider([completion({"provisions": provisions})])

    async def call() -> PolicyPagesResponse:
        async with provider.client() as http:
            request = PolicyPagesRequest.model_construct(
                document_id=uuid4(), media_type="application/pdf", first_page=first, last_page=last,
                content=GOLD.read_bytes(),
            )  # fmt: skip
            return await PolicyPipeline(provider.gateway(http)).run(request)

    return asyncio.run(call())


@pytest.mark.parametrize(
    ("kind", "raw", "expected"),
    [
        (PolicyValueKind.AMOUNT, "INR 40,000", ("40000.00", "INR")),
        (PolicyValueKind.AMOUNT, "INR 40,000 per eye", ("40000.00", "INR")),
        (PolicyValueKind.AMOUNT, "INR 2,500 and INR 3,000", None),
        (PolicyValueKind.PERCENTAGE, "2%", ("2.00", "PERCENT")),
        (PolicyValueKind.PERCENTAGE, "2% of Sum Insured per day", ("2.00", "PERCENT")),
        (PolicyValueKind.PERCENTAGE, "1% and 2%", None),
        (PolicyValueKind.DURATION, "24-month", ("24", "MONTHS")),
        (PolicyValueKind.DURATION, "30 days", ("30", "DAYS")),
        (PolicyValueKind.DURATION, "24 consecutive in-patient care hours", ("24", "HOURS")),
        (PolicyValueKind.DURATION, "30 days and 60 days", None),
        (PolicyValueKind.DURATION, "soon", None),
        (PolicyValueKind.TEXT, "  Single   private room ", ("Single private room", "")),
    ],
)
def test_values_are_normalized_by_code_and_ambiguity_is_refused(
    kind: PolicyValueKind, raw: str, expected: tuple[str, str] | None
) -> None:
    assert normalize_value(kind, raw) == expected


def test_a_bare_number_takes_its_unit_only_from_an_unambiguous_quote() -> None:
    assert duration_from_quote("24", "completed in under 24 hours of admission") == ("24", "HOURS")
    assert duration_from_quote("24", "within 24 hours or 30 days") is None
    assert duration_from_quote("24", "within 48 hours") is None


def test_a_value_found_on_the_page_is_present_with_a_region() -> None:
    response = run([provision()])
    item = response.provisions[0].values[0]
    assert (item.state, item.value, item.unit, item.grounding) == (
        ValueState.PRESENT, "40000.00", "INR", Grounding.TEXT_MATCH,
    )  # fmt: skip
    assert item.region is not None


def test_a_value_whose_quote_is_not_on_the_page_is_uncertain() -> None:
    invented = value("INR 4,00,000", "Cataract surgery INR 4,00,000 per eye")
    item = run([provision(values=[invented])]).provisions[0].values[0]
    assert item.state == ValueState.UNCERTAIN
    assert item.problem == ValueProblem.QUOTE_NOT_FOUND
    assert item.value is None


def test_a_value_its_quote_does_not_contain_is_uncertain() -> None:
    mismatched = value("INR 50,000", "Cataract surgery INR 40,000 per eye")
    assert (
        run([provision(values=[mismatched])]).provisions[0].values[0].problem
        == ValueProblem.VALUE_NOT_IN_QUOTE
    )


def test_a_value_on_a_page_outside_the_batch_has_no_source() -> None:
    elsewhere = value("INR 40,000", "Cataract surgery INR 40,000 per eye", page=7)
    assert run([provision(values=[elsewhere])]).provisions[0].values[0].problem == ValueProblem.NO_SOURCE


def test_a_reference_the_text_does_not_state_is_flagged() -> None:
    references = [
        {"target_key": "3.2", "relation": "QUALIFIES"},
        {"target_key": "9.9", "relation": "EXCEPTS"},
    ]
    result = run([provision(references=references)]).provisions[0]
    assert [(ref.target_key, ref.relation, ref.stated_in_text) for ref in result.references] == [
        ("3.2", RelationType.QUALIFIES, True),
        ("9.9", RelationType.EXCEPTS, False),
    ]


def test_invented_provision_text_is_not_verified() -> None:
    real = run([provision()]).provisions[0]
    invented = run(
        [provision(text="2.6 Cataract surgery is fully covered with no limit or waiting period at all")]
    )
    assert real.text_verified
    assert not invented.provisions[0].text_verified


def test_exceptions_stay_separate_from_the_provision() -> None:
    exclusion = provision(
        key="4.1",
        type="EXCLUSION",
        text="4.1 Cosmetic or aesthetic treatment, except reconstruction following an accident.",
        quote="Cosmetic or aesthetic treatment",
        exceptions=["except reconstruction following an accident"],
        references=[],
        values=[],
    )
    result = run([exclusion]).provisions[0]
    assert result.type == ProvisionType.EXCLUSION
    assert result.exceptions == ["except reconstruction following an accident"]


def test_blank_wire_fields_become_nulls() -> None:
    result = run([provision(key="", parent_key="")]).provisions[0]
    assert result.key is None
    assert result.parent_key is None


@pytest.mark.parametrize(("first", "last"), [(3, 2), (1, MAX_BATCH_PAGES + 1)])
def test_an_invalid_batch_is_refused_before_any_model_call(first: int, last: int) -> None:
    with pytest.raises(DocumentUnreadable):
        run([provision()], first, last)


def test_a_batch_past_the_end_of_the_document_is_refused() -> None:
    with pytest.raises(DocumentUnreadable):
        run([provision()], 5, 6)


def test_the_batch_reports_the_pages_it_covered() -> None:
    response = run([provision()], 1, 8)
    assert (response.first_page, response.last_page, response.page_count) == (1, 2, 2)
    assert response.pipeline_version == "policy-1"
