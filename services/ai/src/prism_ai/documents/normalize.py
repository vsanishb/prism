"""Turns values as written into canonical forms. This is deterministic on purpose: models report
what the page says, and code decides what it means (D-029)."""

import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from prism_ai.documents.catalog import ValueKind

# Day first: Prism's documents are Indian (D-043). Month-first dates fail or become an explicit
# conflict during consolidation rather than being guessed here.
DATE_FORMATS = (
    "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y", "%d/%m/%y", "%d-%m-%y", "%d.%m.%y",
    "%d %b %Y", "%d %B %Y", "%d-%b-%Y", "%d-%B-%Y", "%d %b, %Y", "%d %B, %Y",
    "%b %d, %Y", "%B %d, %Y", "%Y-%m-%d",
)  # fmt: skip
ORDINAL_SUFFIX = re.compile(r"(?<=\d)(st|nd|rd|th)\b", re.IGNORECASE)
CURRENCY_MARK = re.compile(r"(?i)(?<![a-z])(inr|rs\.?|rupees|only)(?![a-z])|₹|/-")
# Indian grouping (1,06,000), international grouping (106,000), or none. The final group always has
# three digits: "12,34" could be a misread, so it is refused rather than read as 1234.
AMOUNT_SHAPE = re.compile(r"^-?(\d{1,3}(,\d{2})*,\d{3}|\d{1,3}(,\d{3})+|\d+)(\.\d{1,2})?$")


def parse_date(text: str) -> date | None:
    cleaned = ORDINAL_SUFFIX.sub("", " ".join(text.split()))
    for pattern in DATE_FORMATS:
        try:
            return datetime.strptime(cleaned, pattern).date()
        except ValueError:
            continue
    return None


def parse_amount(text: str) -> Decimal | None:
    """Accepts Indian and international grouping, and refuses anything ambiguous."""
    cleaned = CURRENCY_MARK.sub("", text).strip().strip(":").strip()
    if not AMOUNT_SHAPE.match(cleaned):
        return None
    try:
        return Decimal(cleaned.replace(",", "")).quantize(Decimal("0.01"))
    except InvalidOperation:
        return None


def normalize(kind: ValueKind, text: str) -> str | None:
    """Returns the canonical value, or None if the text cannot be read as that kind."""
    match kind:
        case ValueKind.DATE:
            parsed = parse_date(text)
            return parsed.isoformat() if parsed else None
        case ValueKind.AMOUNT:
            amount = parse_amount(text)
            return format(amount, "f") if amount is not None else None
        case ValueKind.TEXT:
            return " ".join(text.split()) or None
