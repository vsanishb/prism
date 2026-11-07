"""Reads a batch of policy pages into provisions, and proves each value against the page text."""

import asyncio
import re
from decimal import Decimal, InvalidOperation

from prism_ai.documents.ground import contains_tokens, locate_quote, tokens, value_in_quote
from prism_ai.documents.normalize import parse_amount
from prism_ai.documents.pipeline import messages_for
from prism_ai.documents.render import RenderedPage, RenderLimits, render_window
from prism_ai.documents.schemas import Grounding
from prism_ai.gateway.client import ModelGateway, StructuredRequest
from prism_ai.gateway.errors import DocumentUnreadable, ProcessingError
from prism_ai.gateway.routes import Task
from prism_ai.gateway.usage import ModelRun
from prism_ai.policies.schemas import (
    POLICY_PAGES_SCHEMA,
    ExtractedProvision,
    ExtractedReference,
    ExtractedValue,
    PolicyPagesOutput,
    PolicyPagesRequest,
    PolicyPagesResponse,
    PolicyValueKind,
    ProvisionOutput,
    ValueOutput,
    ValueProblem,
    ValueState,
)
from prism_ai.prompts import load_prompt

PIPELINE_VERSION = "policy-1"
MAX_BATCH_PAGES = 8
# A provision's text is checked against the pages, allowing for words split across lines.
TEXT_MATCH_RATIO = 0.9

PERCENTAGE = re.compile(r"(?<![\d.])(\d{1,3}(?:\.\d{1,2})?)\s*(?:%|per\s*cent\b|percent\b)", re.IGNORECASE)
DURATION = re.compile(r"(?<![\d.])(\d{1,4})\s*-?\s*(hours?|hrs?|days?|months?|years?)\b", re.IGNORECASE)
NUMBER = re.compile(r"(?<![\d.])\d{1,4}(?![\d.,])")
DURATION_UNIT = re.compile(r"\b(hours?|hrs?|days?|months?|years?)\b", re.IGNORECASE)
AMOUNT = re.compile(r"(?:inr|rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)", re.IGNORECASE)
DURATION_UNITS = {"h": "HOURS", "d": "DAYS", "m": "MONTHS", "y": "YEARS"}


def only_match(pattern: re.Pattern[str], raw: str) -> re.Match[str] | None:
    """The single match in the text. Two candidates are ambiguous, so neither is chosen."""
    found = list(pattern.finditer(raw))
    return found[0] if len(found) == 1 else None


def normalize_value(kind: PolicyValueKind, raw: str) -> tuple[str, str] | None:
    """Returns (value, unit), or None if the text cannot be read as that kind. Deterministic (D-029).

    A model sometimes includes words around the value, such as "2% of Sum Insured". The value is
    taken only when exactly one candidate is present, and the raw text must still be in the quote.
    """
    match kind:
        case PolicyValueKind.AMOUNT:
            amount = parse_amount(raw)
            if amount is None:
                found = only_match(AMOUNT, raw)
                amount = parse_amount(found[1]) if found else None
            return (format(amount, "f"), "INR") if amount is not None and amount >= 0 else None
        case PolicyValueKind.PERCENTAGE:
            found = only_match(PERCENTAGE, raw)
            if not found:
                return None
            try:
                return format(Decimal(found[1]).quantize(Decimal("0.01")), "f"), "PERCENT"
            except InvalidOperation:
                return None
        case PolicyValueKind.DURATION:
            found = only_match(DURATION, raw)
            if found:
                return found[1], DURATION_UNITS[found[2][0].lower()]
            # "24 consecutive in-patient care hours": one number and one unit, with words between.
            numbers, units = NUMBER.findall(raw), DURATION_UNIT.findall(raw)
            if len(numbers) == 1 and len(units) == 1:
                return numbers[0], DURATION_UNITS[units[0][0].lower()]
            return None
        case PolicyValueKind.TEXT:
            text = " ".join(raw.split())
            return (text, "") if text else None


def text_verified(text: str, pages: list[RenderedPage]) -> bool:
    """True if almost every word of the provision appears on the pages, so the text was not invented."""
    wanted = tokens(text)
    if not wanted:
        return False
    available = {token for page in pages for token in tokens(page.text)}
    return sum(token in available for token in wanted) / len(wanted) >= TEXT_MATCH_RATIO


def duration_from_quote(raw: str, quote: str) -> tuple[str, str] | None:
    """A bare number reported as a duration takes its unit from the quote, but only when the quote
    holds that one number and one unit, so that "24" in "under 24 hours" reads as 24 hours."""
    if not raw.strip().isdigit():
        return None
    reading = normalize_value(PolicyValueKind.DURATION, quote)
    return reading if reading is not None and reading[0] == raw.strip() else None


def resolve_value(raw: ValueOutput, pages: dict[int, RenderedPage]) -> ExtractedValue:
    def result(state: ValueState, problem: ValueProblem | None, **values: object) -> ExtractedValue:
        base: dict[str, object] = {
            "name": raw.name.strip(),
            "kind": raw.kind,
            "raw_value": raw.raw,
            "value": None,
            "unit": None,
            "qualifier": raw.qualifier.strip() or None,
            "state": state,
            "page": raw.page if raw.page in pages else None,
            "quote": raw.quote or None,
            "region": None,
            "grounding": Grounding.NONE,
            "problem": problem,
        }
        return ExtractedValue.model_validate(base | values)

    page = pages.get(raw.page)
    if page is None or not raw.quote or not raw.raw:
        return result(ValueState.UNCERTAIN, ValueProblem.NO_SOURCE)
    normalized = normalize_value(raw.kind, raw.raw)
    if normalized is None and raw.kind == PolicyValueKind.DURATION:
        normalized = duration_from_quote(raw.raw, raw.quote)
    if not page.has_text_layer:
        # Every critical policy value is confirmed by a person before publication (D-030). On a scan
        # there is no text to check, so the value goes to that review marked uncertain.
        found = {"value": normalized[0], "unit": normalized[1] or None} if normalized else {}
        return result(ValueState.UNCERTAIN, ValueProblem.SCANNED_PAGE, grounding=Grounding.PAGE_ONLY, **found)
    match = locate_quote(raw.quote, page)
    if match is None:
        return result(ValueState.UNCERTAIN, ValueProblem.QUOTE_NOT_FOUND)
    located = {"region": match.region, "grounding": Grounding.TEXT_MATCH}
    if not value_in_quote(raw.raw, raw.quote):
        return result(ValueState.UNCERTAIN, ValueProblem.VALUE_NOT_IN_QUOTE, **located)
    if normalized is None:
        return result(ValueState.UNCERTAIN, ValueProblem.UNREADABLE_FORMAT, **located)
    value, unit = normalized
    return result(ValueState.PRESENT, None, value=value, unit=unit or None, **located)


def resolve_provision(raw: ProvisionOutput, pages: dict[int, RenderedPage]) -> ExtractedProvision:
    page = pages.get(raw.page)
    region = None
    grounding = Grounding.NONE
    if page is not None and page.has_text_layer:
        match = locate_quote(raw.quote, page)
        if match is not None:
            region, grounding = match.region, Grounding.TEXT_MATCH
    elif page is not None:
        grounding = Grounding.PAGE_ONLY
    return ExtractedProvision(
        key=raw.key.strip() or None,
        type=raw.type,
        title=" ".join(raw.title.split()),
        text=" ".join(raw.text.split()),
        page=raw.page,
        quote=raw.quote,
        region=region,
        grounding=grounding,
        text_verified=text_verified(raw.text, list(pages.values())),
        parent_key=raw.parent_key.strip() or None,
        exceptions=[" ".join(item.split()) for item in raw.exceptions if item.strip()],
        references=[
            ExtractedReference(
                target_key=reference.target_key.strip(),
                relation=reference.relation,
                stated_in_text=contains_tokens(raw.text, reference.target_key),
            )
            for reference in raw.references
            if reference.target_key.strip()
        ],
        values=[resolve_value(value, pages) for value in raw.values],
        continues_on_next_page=raw.continues_on_next_page,
        continued_from_previous_page=raw.continued_from_previous_page,
    )


class PolicyPipeline:
    def __init__(self, gateway: ModelGateway, limits: RenderLimits | None = None) -> None:
        self._gateway = gateway
        self._limits = limits
        self._prompt = load_prompt("policy", 1)

    async def run(self, request: PolicyPagesRequest) -> PolicyPagesResponse:
        runs: list[ModelRun] = []
        try:
            return await self._run(request, runs)
        except ProcessingError as error:
            error.runs = runs
            raise

    async def _run(self, request: PolicyPagesRequest, runs: list[ModelRun]) -> PolicyPagesResponse:
        span = request.last_page - request.first_page
        if span < 0 or span >= MAX_BATCH_PAGES:
            raise DocumentUnreadable("Page batch is out of range")
        window = await asyncio.to_thread(
            render_window,
            request.content,
            request.media_type,
            request.first_page,
            request.last_page,
            self._limits,
        )
        if not window.pages:
            raise DocumentUnreadable("Page batch is outside the document")
        instruction = (
            f"Pages {window.pages[0].number} to {window.pages[-1].number} of a {window.page_count}-page "
            "health insurance policy. Return every provision on these pages."
        )
        output = await self._gateway.complete(
            StructuredRequest(
                task=Task.POLICY_EXTRACTION,
                prompt_version=self._prompt.version,
                schema_name="policy_pages",
                schema=POLICY_PAGES_SCHEMA,
                messages=messages_for(self._prompt, instruction, window.pages),
                parse=PolicyPagesOutput.model_validate,
            ),
            runs,
        )
        pages = {page.number: page for page in window.pages}
        return PolicyPagesResponse(
            pipeline_version=PIPELINE_VERSION,
            page_count=window.page_count,
            first_page=window.pages[0].number,
            last_page=window.pages[-1].number,
            provisions=[resolve_provision(provision, pages) for provision in output.provisions],
            runs=runs,
        )
