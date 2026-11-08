"""The Policy Agent's side of the local model stand-in. Deterministic answers that follow the
contract, so that end-to-end tests exercise planning, validation, citations, and calculation
without a real model. Test tooling only."""

import re
from typing import Any

EVIDENCE_LINE = re.compile(r"^\[(E\d+)\] [^\n]*\n([^\n]+)", re.MULTILINE)
VALUE_LINE = re.compile(r"^\[(V\d+)\] in (E\d+): .*\((AMOUNT|PERCENTAGE|DURATION)\)$", re.MULTILINE)
ISSUE_KIND = re.compile(r"<issue>\n(\w+):")
ROLES = {"AMOUNT": "AMOUNT_LIMIT", "PERCENTAGE": "PERCENT_OF_SUM_INSURED", "DURATION": "WAITING_PERIOD"}


def plan() -> dict[str, Any]:
    return {
        "issues": [
            {
                "kind": "COVERAGE",
                "question": "Is in-patient treatment covered?",
                "queries": ["in-patient hospitalization"],
            },
            {"kind": "LIMIT", "question": "Does a sub-limit apply?", "queries": ["cataract surgery limit"]},
        ]
    }


def finding(text: str) -> dict[str, Any]:
    """Cites the first provision found, word for word, and applies its value if it has one. The age
    a co-payment depends on is never in the facts, so cost sharing is left to a person."""
    base: dict[str, Any] = {
        "action": "FINDING",
        "follow_up_queries": [],
        "facts_used": ["admission_date"],
        "unresolved": [],
        "applied_values": [],
        "reasoning": "Stand-in reasoning for tests.",
    }
    kind = ISSUE_KIND.search(text)
    if kind and kind.group(1) == "COST_SHARING":
        return base | {
            "status": "NEEDS_HUMAN_INTERPRETATION",
            "citations": [],
            "unresolved": ["The insured person's age is not in the claim documents."],
        }
    evidence = EVIDENCE_LINE.search(text)
    if evidence is None:
        return base | {"status": "INSUFFICIENT_EVIDENCE", "citations": []}
    evidence_id, first_line = evidence.group(1), evidence.group(2)
    value = next((match for match in VALUE_LINE.finditer(text) if match.group(2) == evidence_id), None)
    applied = [{"value_id": value.group(1), "role": ROLES[value.group(3)]}] if value else []
    return base | {
        "status": "LIMITED" if applied else "SUPPORTED",
        "citations": [{"evidence_id": evidence_id, "quote": first_line[:60]}],
        "applied_values": applied,
    }
