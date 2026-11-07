from decimal import Decimal

import pytest

from prism_ai.documents.catalog import ValueKind
from prism_ai.documents.ground import contains_tokens, locate_quote, tokens, value_in_quote
from prism_ai.documents.normalize import normalize, parse_amount, parse_date
from prism_ai.documents.render import RenderedPage, Word


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("14/03/2026", "2026-03-14"),
        ("14-03-2026", "2026-03-14"),
        ("14.03.26", "2026-03-14"),
        ("05-Jul-2026", "2026-07-05"),
        ("9th July 2026", "2026-07-09"),
        ("July 9, 2026", "2026-07-09"),
        ("2026-03-14", "2026-03-14"),
        ("  14 / 03 / 2026 ", None),
        ("31/02/2026", None),
        ("13/13/2026", None),
        ("next Tuesday", None),
        ("", None),
    ],
)
def test_dates_are_day_first(text: str, expected: str | None) -> None:
    parsed = parse_date(text)
    assert (parsed.isoformat() if parsed else None) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("1,06,000.00", "106000.00"),
        ("106,000.00", "106000.00"),
        ("INR 1,06,000.00", "106000.00"),
        ("Rs. 11,000/-", "11000.00"),
        ("Rs 500", "500.00"),
        ("₹2,500.5", "2500.50"),
        ("11000 only", "11000.00"),
        ("-1,200.00", "-1200.00"),
        ("0.10", "0.10"),
    ],
)
def test_amounts_read_indian_and_international_grouping(text: str, expected: str) -> None:
    assert parse_amount(text) == Decimal(expected)


@pytest.mark.parametrize("text", ["", "abc", "1.2.3", "12,34", "1,06,000.005", "ten thousand", "1 000"])
def test_ambiguous_amounts_are_refused(text: str) -> None:
    assert parse_amount(text) is None


def test_normalize_by_kind() -> None:
    assert normalize(ValueKind.DATE, "14/03/2026") == "2026-03-14"
    assert normalize(ValueKind.AMOUNT, "INR 10") == "10.00"
    assert normalize(ValueKind.TEXT, "  Acute   appendicitis\n") == "Acute appendicitis"
    assert normalize(ValueKind.TEXT, "   ") is None
    assert normalize(ValueKind.DATE, "unclear") is None


def page(*words: tuple[str, float, float]) -> RenderedPage:
    """Each word sits on a row of its own height, at the given position."""
    return RenderedPage(
        number=1,
        image="",
        text=" ".join(word[0] for word in words),
        words=[Word(text=text, box=(x, y, x + 0.1, y + 0.02)) for text, x, y in words],
    )


LABELLED = page(("Date", 0.1, 0.3), ("of", 0.2, 0.3), ("Admission:", 0.3, 0.3), ("14/03/2026", 0.5, 0.3))


def test_a_quote_resolves_to_the_box_around_its_words() -> None:
    match = locate_quote("Admission: 14/03/2026", LABELLED)
    assert match is not None
    assert match.region == pytest.approx((0.3, 0.3, 0.6, 0.32))


def test_matching_ignores_case_spacing_and_punctuation() -> None:
    assert locate_quote("date of   ADMISSION 14 03 2026", LABELLED) is not None


def test_a_quote_not_on_the_page_is_not_found() -> None:
    assert locate_quote("Date of Discharge: 14/03/2026", LABELLED) is None


def test_an_empty_quote_is_not_found() -> None:
    assert locate_quote("...", LABELLED) is None


def test_a_partial_word_does_not_count_as_a_match() -> None:
    assert locate_quote("Admiss", LABELLED) is None


def test_value_must_come_from_its_quote() -> None:
    assert value_in_quote("14/03/2026", "Date of Admission: 14/03/2026")
    assert not value_in_quote("15/03/2026", "Date of Admission: 14/03/2026")
    assert not value_in_quote("", "Date of Admission: 14/03/2026")
    assert not value_in_quote("1,000", "Total 11,000")


def test_contains_tokens_needs_a_whole_run() -> None:
    assert contains_tokens("Synthetic Care Hospital, Pune (Fictional)", "Synthetic Care Hospital")
    assert not contains_tokens("Synthetic Care Hospital", "Synthetic Hospital")


def test_tokens_fold_unicode_width_and_case() -> None:
    assert tokens("\uff32\uff33. 500") == ["rs", "500"]
