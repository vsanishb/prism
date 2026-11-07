"""Classifies a document, extracts it window by window, and proves each value against its source."""

import asyncio
from collections.abc import Sequence
from typing import Any

import structlog

from prism_ai.documents.catalog import TYPES_WITH_LINE_ITEMS, DocumentType, FieldSpec, ValueKind, fields_for
from prism_ai.documents.ground import contains_tokens, locate_quote, value_in_quote
from prism_ai.documents.normalize import normalize, parse_amount
from prism_ai.documents.render import RenderedPage, RenderedWindow, RenderLimits, render_window
from prism_ai.documents.schemas import (
    CLASSIFICATION_SCHEMA,
    Classification,
    ClassificationOutput,
    Confidence,
    ExtractedField,
    ExtractedLineItem,
    ExtractionOutput,
    ExtractRequest,
    ExtractResponse,
    FieldOutput,
    Grounding,
    LineItemOutput,
    RereadOutput,
    UncertaintyReason,
    ValueState,
    extraction_schema,
    reread_schema,
)
from prism_ai.gateway.client import Message, ModelGateway, StructuredRequest
from prism_ai.gateway.errors import ModelOutputInvalid, ProcessingError
from prism_ai.gateway.routes import Task
from prism_ai.gateway.usage import ModelRun
from prism_ai.prompts import Prompt, load_prompt

logger = structlog.get_logger()

PIPELINE_VERSION = "doc-1"
WINDOW_PAGES = 8
CLASSIFICATION_PAGES = 2
MAX_CONCURRENT_RENDERS = 2
NO_TEXT_LAYER = "(scanned page: no text layer)"


def page_content(pages: Sequence[RenderedPage]) -> list[dict[str, Any]]:
    """Labels each page. Text is fenced as data, and a document cannot close the fence itself."""
    content: list[dict[str, Any]] = []
    for page in pages:
        text = page.text.replace("</page_text>", "") if page.has_text_layer else NO_TEXT_LAYER
        content.append({"type": "text", "text": f"Page {page.number}\n<page_text>{text}</page_text>"})
        content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{page.image}"}})
    return content


def messages_for(prompt: Prompt, instruction: str, pages: Sequence[RenderedPage]) -> list[Message]:
    return [
        {"role": "system", "content": prompt.text},
        {"role": "user", "content": [{"type": "text", "text": instruction}, *page_content(pages)]},
    ]


def parse_extraction(specs: tuple[FieldSpec, ...]) -> Any:
    def parse(raw: Any) -> ExtractionOutput:
        output = ExtractionOutput.model_validate(raw)
        if set(output.fields) != {spec.name for spec in specs}:
            raise ValueError("output did not address exactly the requested fields")
        return output

    return parse


def uncertain(spec: FieldSpec, raw: FieldOutput, reason: UncertaintyReason, **values: Any) -> ExtractedField:
    fields: dict[str, Any] = {
        "name": spec.name,
        "kind": spec.kind,
        "critical": spec.critical,
        "state": ValueState.UNCERTAIN,
        "confidence": raw.confidence,
        "raw_value": raw.value,
        "value": None,
        "page": raw.page,
        "quote": raw.quote,
        "region": None,
        "grounding": Grounding.NONE,
        "reason": reason,
    }
    return ExtractedField(**(fields | values))


def missing(spec: FieldSpec) -> ExtractedField:
    return ExtractedField(
        name=spec.name,
        kind=spec.kind,
        critical=spec.critical,
        state=ValueState.MISSING,
        confidence=Confidence.HIGH,
        raw_value=None,
        value=None,
        page=None,
        quote=None,
        region=None,
        grounding=Grounding.NONE,
        reason=None,
    )


def resolve_field(spec: FieldSpec, raw: FieldOutput, pages: dict[int, RenderedPage]) -> ExtractedField:
    """Decides a field's state in code. The model proposes; only checked values become PRESENT."""
    if raw.state == "MISSING":
        return missing(spec)
    if raw.value is None or raw.page is None or raw.quote is None or raw.page not in pages:
        return uncertain(spec, raw, UncertaintyReason.NO_SOURCE)

    page = pages[raw.page]
    region = None
    grounding = Grounding.PAGE_ONLY
    if page.has_text_layer:
        match = locate_quote(raw.quote, page)
        if match is None:
            return uncertain(spec, raw, UncertaintyReason.QUOTE_NOT_FOUND)
        region = match.region
        grounding = Grounding.TEXT_MATCH
    sourced = {"region": region, "grounding": grounding}
    if not value_in_quote(raw.value, raw.quote):
        return uncertain(spec, raw, UncertaintyReason.VALUE_NOT_IN_QUOTE, **sourced)
    value = normalize(spec.kind, raw.value)
    if value is None:
        return uncertain(spec, raw, UncertaintyReason.UNREADABLE_FORMAT, **sourced)
    if raw.state == "UNCERTAIN":
        return uncertain(spec, raw, UncertaintyReason.MODEL_UNSURE, value=value, **sourced)
    return ExtractedField(
        name=spec.name,
        kind=spec.kind,
        critical=spec.critical,
        state=ValueState.PRESENT,
        confidence=raw.confidence,
        raw_value=raw.value,
        value=value,
        page=raw.page,
        quote=raw.quote,
        region=region,
        grounding=grounding,
        reason=None,
    )


def resolve_line_item(raw: LineItemOutput, pages: dict[int, RenderedPage]) -> ExtractedLineItem:
    page = pages.get(raw.page)
    amount = parse_amount(raw.amount)
    region = None
    grounding = Grounding.NONE
    if page is not None:
        match = locate_quote(raw.quote, page) if page.has_text_layer else None
        grounding = Grounding.PAGE_ONLY if not page.has_text_layer else Grounding.NONE
        if match is not None:
            region = match.region
            grounding = Grounding.TEXT_MATCH
    trusted = amount is not None and grounding != Grounding.NONE and value_in_quote(raw.amount, raw.quote)
    return ExtractedLineItem(
        description=" ".join(raw.description.split()),
        quantity=raw.quantity,
        unit_price=raw.unit_price,
        amount=format(amount, "f") if amount is not None else None,
        raw_amount=raw.amount,
        state=ValueState.PRESENT if trusted else ValueState.UNCERTAIN,
        page=raw.page,
        quote=raw.quote,
        region=region,
        grounding=grounding,
    )


def merge_windows(specs: tuple[FieldSpec, ...], windows: list[list[ExtractedField]]) -> list[ExtractedField]:
    """One result per field. A page that omits a value does not outweigh one that states it, and
    pages that state different values leave the field uncertain instead of picking one."""
    merged: list[ExtractedField] = []
    for index in range(len(specs)):
        candidates = [window[index] for window in windows]
        present = [field for field in candidates if field.state == ValueState.PRESENT]
        unsure = [field for field in candidates if field.state == ValueState.UNCERTAIN]
        if len({field.value for field in present}) > 1:
            merged.append(present[0].model_copy(update={
                "state": ValueState.UNCERTAIN, "value": None, "reason": UncertaintyReason.PAGES_DISAGREE,
            }))  # fmt: skip
        elif present:
            merged.append(present[0])
        else:
            merged.append(unsure[0] if unsure else candidates[0])
    return merged


class DocumentPipeline:
    def __init__(self, gateway: ModelGateway, limits: RenderLimits | None = None) -> None:
        self._gateway = gateway
        self._limits = limits
        self._renders = asyncio.Semaphore(MAX_CONCURRENT_RENDERS)
        self._classify_prompt = load_prompt("classify", 1)
        self._extract_prompt = load_prompt("extract", 1)
        self._reread_prompt = load_prompt("reread", 1)

    async def run(self, request: ExtractRequest) -> ExtractResponse:
        runs: list[ModelRun] = []
        try:
            return await self._run(request, runs)
        except ProcessingError as error:
            error.runs = runs
            raise

    async def _run(self, request: ExtractRequest, runs: list[ModelRun]) -> ExtractResponse:
        first = await self._render(request, 1, WINDOW_PAGES)
        classification = await self._classify(request, first.pages[:CLASSIFICATION_PAGES], runs)
        # A clear reading of the document outranks the upload label. Core flags the disagreement.
        document_type = (
            classification.document_type
            if classification.confidence == Confidence.HIGH
            else request.declared_type
        )
        specs = fields_for(document_type)

        field_windows: list[list[ExtractedField]] = []
        line_items: list[ExtractedLineItem] = []
        window = first
        start = 1
        while True:
            fields, items = await self._extract_window(document_type, specs, window.pages, runs)
            field_windows.append(fields)
            line_items.extend(items)
            start += WINDOW_PAGES
            if start > first.page_count:
                break
            window = await self._render(request, start, start + WINDOW_PAGES - 1)

        logger.info(
            "document_extracted",
            document_id=str(request.document_id),
            page_count=first.page_count,
            model_calls=len(runs),
        )
        return ExtractResponse(
            pipeline_version=PIPELINE_VERSION,
            page_count=first.page_count,
            classification=classification,
            fields=merge_windows(specs, field_windows),
            line_items=line_items,
            runs=runs,
        )

    async def _render(self, request: ExtractRequest, first: int, last: int) -> RenderedWindow:
        async with self._renders:
            return await asyncio.to_thread(
                render_window, request.content, request.media_type, first, last, self._limits
            )

    async def _classify(
        self, request: ExtractRequest, pages: Sequence[RenderedPage], runs: list[ModelRun]
    ) -> Classification:
        output = await self._gateway.complete(
            StructuredRequest(
                task=Task.CLASSIFICATION,
                prompt_version=self._classify_prompt.version,
                schema_name="document_classification",
                schema=CLASSIFICATION_SCHEMA,
                messages=messages_for(self._classify_prompt, "Classify this document.", pages),
                parse=ClassificationOutput.model_validate,
            ),
            runs,
        )
        return Classification(
            document_type=output.document_type,
            confidence=output.confidence,
            declared_type=request.declared_type,
        )

    async def _extract_window(
        self,
        document_type: DocumentType,
        specs: tuple[FieldSpec, ...],
        pages: list[RenderedPage],
        runs: list[ModelRun],
    ) -> tuple[list[ExtractedField], list[ExtractedLineItem]]:
        wanted = "\n".join(f"- {spec.name}: {spec.description}" for spec in specs)
        items = (
            "Return every charge row as line_items."
            if document_type in TYPES_WITH_LINE_ITEMS
            else "This document type has no line items: return an empty list."
        )
        output = await self._gateway.complete(
            StructuredRequest(
                task=Task.EXTRACTION,
                prompt_version=self._extract_prompt.version,
                schema_name="document_extraction",
                schema=extraction_schema(specs),
                messages=messages_for(
                    self._extract_prompt,
                    f"Document type: {document_type}\nFields:\n{wanted}\n{items}",
                    pages,
                ),
                parse=parse_extraction(specs),
            ),
            runs,
        )
        by_number = {page.number: page for page in pages}
        fields = [resolve_field(spec, output.fields[spec.name], by_number) for spec in specs]
        fields = await self._reread_scans(specs, fields, pages, runs)
        line_items = []
        if document_type in TYPES_WITH_LINE_ITEMS:
            line_items = [resolve_line_item(item, by_number) for item in output.line_items]
        return fields, line_items

    async def _reread_scans(
        self,
        specs: tuple[FieldSpec, ...],
        fields: list[ExtractedField],
        pages: list[RenderedPage],
        runs: list[ModelRun],
    ) -> list[ExtractedField]:
        """A scan offers no text to check a quote against, so critical values are read a second
        time, without showing the first reading, and must agree."""
        to_check = [
            field for field in fields
            if field.critical and field.state == ValueState.PRESENT and field.grounding == Grounding.PAGE_ONLY
        ]  # fmt: skip
        if not to_check:
            return fields
        names = {field.name for field in to_check}
        checked_specs = tuple(spec for spec in specs if spec.name in names)
        wanted = "\n".join(f"- {spec.name}: {spec.description}" for spec in checked_specs)

        def parse(raw: Any) -> RereadOutput:
            output = RereadOutput.model_validate(raw)
            if set(output.fields) != names:
                raise ValueError("output did not address exactly the requested fields")
            return output

        try:
            output = await self._gateway.complete(
                StructuredRequest(
                    task=Task.REREAD,
                    prompt_version=self._reread_prompt.version,
                    schema_name="document_reread",
                    schema=reread_schema(checked_specs),
                    messages=messages_for(self._reread_prompt, f"Fields:\n{wanted}", pages),
                    parse=parse,
                ),
                runs,
            )
        except ModelOutputInvalid:
            return [self._mark(field, names, UncertaintyReason.REREAD_UNAVAILABLE) for field in fields]

        kinds = {spec.name: spec.kind for spec in checked_specs}
        return [
            self._compare(field, output, kinds[field.name]) if field.name in names else field
            for field in fields
        ]

    @staticmethod
    def _mark(field: ExtractedField, names: set[str], reason: UncertaintyReason) -> ExtractedField:
        if field.name not in names:
            return field
        return field.model_copy(update={"state": ValueState.UNCERTAIN, "reason": reason})

    @classmethod
    def _compare(cls, field: ExtractedField, output: RereadOutput, kind: ValueKind) -> ExtractedField:
        second = output.fields[field.name].value
        normalized = normalize(kind, second) if second is not None else None
        first = field.value
        agrees = normalized is not None and first is not None and cls._agree(kind, first, normalized)
        return field if agrees else cls._mark(field, {field.name}, UncertaintyReason.REREAD_DISAGREES)

    @staticmethod
    def _agree(kind: ValueKind, first: str, second: str) -> bool:
        """Dates and amounts must match exactly. For text, one reading may simply stop earlier,
        such as a hospital's name with or without its city."""
        if kind != ValueKind.TEXT:
            return first == second
        return contains_tokens(first, second) or contains_tokens(second, first)
