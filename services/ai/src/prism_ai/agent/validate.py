"""Checks a model's finding against what was actually retrieved and what the claim actually states.

Pure, so that every rule is tested without a model. A model proposes; this decides what stands:
a citation must quote a provision retrieved in this run, a fact must be established before a
finding may rest on it, and a value may be applied only from a provision the finding cites.
"""

import re
import unicodedata
from dataclasses import dataclass
from uuid import UUID

from prism_ai.agent.schemas import (
    EVIDENCED_STATUSES,
    ROLE_KINDS,
    AnalysisOutput,
    AppliedValue,
    Citation,
    ClaimFact,
    Finding,
    FindingStatus,
    IssueKind,
    PolicyValue,
    Problem,
    ValueRole,
)
from prism_ai.policies.schemas import ProvisionType

MIN_QUOTE_CHARS = 8
# Curly quotes and long dashes, as a model or a PDF may render them, compare as their plain forms.
TYPOGRAPHIC = {0x2018: "'", 0x2019: "'", 0x201C: '"', 0x201D: '"', 0x2013: "-", 0x2014: "-"}
FACT_LABELS = {
    "admission_date": "The admission date",
    "discharge_date": "The discharge date",
    "claimed_amount": "The claimed amount",
    "diagnosis": "The diagnosis",
    "procedure": "The procedure",
    "test_name": "The test",
    "findings": "The test findings",
    "medicines": "The medicines",
}
# Some issues cannot be settled without these facts, whether or not the model named them. A
# waiting period needs the admission date; cover and exclusions need to know what was treated.
TREATMENT = ("diagnosis", "procedure")
REQUIRED_FACTS: dict[IssueKind, tuple[tuple[str, ...], ...]] = {
    IssueKind.WAITING_PERIOD: (("admission_date",), TREATMENT),
    IssueKind.COVERAGE: (TREATMENT,),
    IssueKind.EXCLUSION: (TREATMENT,),
}
STATE_WORDS = {
    "MISSING": "not stated in the documents",
    "UNCERTAIN": "uncertain",
    "CONFLICTING": "conflicting between documents",
}


@dataclass(frozen=True)
class Evidence:
    """A provision retrieved in this run, under the short ID the model sees."""

    evidence_id: str
    provision_id: UUID
    key: str | None
    title: str
    type: ProvisionType
    text: str


def normalize(text: str) -> str:
    """Compares quotes by content: case, quote marks, dashes, and spacing do not count."""
    text = unicodedata.normalize("NFKC", text).casefold()
    text = text.translate(TYPOGRAPHIC)
    text = text.strip().strip("\"'").strip()
    text = re.sub(r"(\.\.\.|\u2026)$", "", text).strip()
    return re.sub(r"\s+", " ", text)


def quote_matches(quote: str, source: str) -> bool:
    wanted = normalize(quote)
    return len(wanted) >= MIN_QUOTE_CHARS and wanted in normalize(source)


def fact_gap(fact: ClaimFact) -> str:
    label = FACT_LABELS.get(fact.name, fact.name.replace("_", " ").capitalize())
    return f"{label} is {STATE_WORDS.get(fact.state, fact.state.lower())}."


def validated(
    *,
    position: int,
    kind: IssueKind,
    question: str,
    output: AnalysisOutput,
    final_round: bool,
    evidence: dict[str, Evidence],
    facts: dict[str, ClaimFact],
    values: dict[str, PolicyValue],
    rounds: int,
    queries: list[str],
) -> Finding:
    problems: list[Problem] = []
    unresolved = [item.strip() for item in output.unresolved if item.strip()]
    status = output.status

    if output.action == "SEARCH_MORE":
        # Only reached on the last round: the search allowance ran out before the issue settled.
        problems.append(Problem.SEARCH_LIMIT_REACHED)
        unresolved.append("The wording found in the search allowance did not settle this issue.")
        status = FindingStatus.INSUFFICIENT_EVIDENCE

    citations: list[Citation] = []
    seen: set[tuple[UUID, str]] = set()
    citation_failed = False
    rejected = 0
    for cited in output.citations:
        source = evidence.get(cited.evidence_id.strip())
        if source is None:
            problems.append(Problem.CITATION_UNKNOWN)
            citation_failed = True
            rejected += 1
            continue
        if not quote_matches(cited.quote, source.text):
            problems.append(Problem.CITATION_QUOTE_MISMATCH)
            citation_failed = True
            rejected += 1
            continue
        identity = (source.provision_id, normalize(cited.quote))
        if identity not in seen:
            seen.add(identity)
            citations.append(
                Citation(
                    provision_id=source.provision_id,
                    key=source.key,
                    title=source.title,
                    type=source.type,
                    quote=cited.quote.strip(),
                )
            )

    facts_used: list[UUID] = []
    for name in dict.fromkeys(item.strip() for item in output.facts_used):
        fact = facts.get(name)
        if fact is None:
            problems.append(Problem.FACT_UNKNOWN)
            continue
        facts_used.append(fact.id)
        if fact.state != "PRESENT":
            # Carried forward as an unknown, never assumed (plan: Uncertainty).
            problems.append(Problem.FACT_NOT_ESTABLISHED)
            unresolved.append(fact_gap(fact))

    cited_provisions = {citation.provision_id for citation in citations}
    applied: list[AppliedValue] = []
    for item in output.applied_values:
        value = values.get(item.value_id.strip())
        if (
            value is None
            or value.provision_id not in cited_provisions
            or value.kind not in ROLE_KINDS[item.role]
        ):
            problems.append(Problem.VALUE_REJECTED)
            continue
        if all(existing.value_id != value.id or existing.role != item.role for existing in applied):
            applied.append(AppliedValue(value_id=value.id, role=item.role))

    roles = {item.role for item in applied}
    needed = list(REQUIRED_FACTS.get(kind, ()))
    if ValueRole.WAITING_PERIOD in roles:
        needed.append(("admission_date",))
    for alternatives in needed:
        if not any(name in facts and facts[name].state == "PRESENT" for name in alternatives):
            problems.append(Problem.FACT_NOT_ESTABLISHED)
            missing = [facts[name] for name in alternatives if name in facts]
            unresolved += [fact_gap(fact) for fact in missing] or [
                f"{FACT_LABELS.get(alternatives[0], alternatives[0])} is not stated in the documents."
            ]

    if status in EVIDENCED_STATUSES:
        if citation_failed:
            # An unsupported citation invalidates the finding (system-flows.md, Policy Agent).
            status = FindingStatus.INSUFFICIENT_EVIDENCE
            unresolved.append(
                "A citation could not be matched to the policy wording, so this needs checking by a person."
            )
        elif not citations:
            problems.append(Problem.NO_VALID_CITATION)
            status = FindingStatus.INSUFFICIENT_EVIDENCE
            unresolved.append("No provision of the wording was cited for this.")
        elif Problem.FACT_NOT_ESTABLISHED in problems:
            status = FindingStatus.INSUFFICIENT_EVIDENCE
    if status not in EVIDENCED_STATUSES:
        applied = []

    return Finding(
        position=position,
        kind=kind,
        question=question.strip(),
        status=status,
        proposed_status=output.status if output.status != status else None,
        facts_used=facts_used,
        citations=citations,
        reasoning=output.reasoning.strip(),
        unresolved=list(dict.fromkeys(unresolved)),
        applied_values=applied,
        problems=list(dict.fromkeys(problems)),
        rejected_citations=rejected,
        rounds=rounds,
        queries=queries,
    )
