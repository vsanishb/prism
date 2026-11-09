"""An optional, plain-language summary of a stored risk screening (D-086).

The model sees only the signals core sends, with no names, and writes about them. Code decides
whether the summary may be shown: it must cite signals that exist, use only numbers found in them,
and must not assert fraud, guilt, or any decision. The summary is written after the screening is
stored, and nothing in it can change a score, band, or route.
"""

import re
from functools import partial

import structlog

from prism_ai.gateway.client import Message, ModelGateway, StructuredRequest
from prism_ai.gateway.errors import ProcessingError
from prism_ai.gateway.routes import Task
from prism_ai.gateway.usage import ModelRun
from prism_ai.prompts import load_prompt
from prism_ai.retrieval.pipeline import once_more
from prism_ai.risk.schemas import (
    MAX_SUMMARY_CHARS,
    SUMMARY_SCHEMA,
    ExplanationRequest,
    ExplanationResponse,
    ExplanationStatus,
    SummaryOutput,
)

logger = structlog.get_logger()

SIGNAL_REFERENCE = re.compile(r"\bS[1-9][0-9]?\b")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
# Words that would turn a screening into an accusation or a decision. Checked case-insensitively.
FORBIDDEN = re.compile(
    r"\bfraudulent\b|\bfraudster|\bcommitted\s+fraud\b|\bconfirmed\s+fraud\b|\bfraud\s+(?:has\s+been\s+|was\s+|is\s+)?"
    r"(?:confirmed|proven|established|committed|detected)\b|\b(?:is|are|was|were)\s+(?:a\s+)?fraud\b|\bguilty\b"
    r"|\bliar\b|\blying\b|\bcriminal\b|\breject\w*|\bden(?:y|ied|ial)\b|\bdecline\w*|\brepudiat\w*|\bapprov\w*"
    r"|\bsettle\w*|\bshould\s+(?:not\s+)?be\s+paid\b|\bpayable\b",
    re.IGNORECASE,
)


def _numbers(text: str) -> set[str]:
    return {match.replace(",", "") for match in NUMBER.findall(SIGNAL_REFERENCE.sub(" ", text))}


def checked(output: SummaryOutput, request: ExplanationRequest) -> tuple[str, list[str]]:
    """Raises ValueError, which the gateway turns into a repair, when the summary may not be shown."""
    text = " ".join(output.summary.split())
    if not text:
        raise ValueError("The summary is empty.")
    if len(text) > MAX_SUMMARY_CHARS:
        raise ValueError(f"The summary is longer than {MAX_SUMMARY_CHARS} characters.")
    known = {signal.id for signal in request.signals}
    cited = set(SIGNAL_REFERENCE.findall(text)) | set(output.signals_cited)
    if not cited:
        raise ValueError("The summary must cite the signals it describes, as [S1].")
    unknown = sorted(cited - known)
    if unknown:
        raise ValueError(f"The summary cites signals that were not given: {', '.join(unknown)}.")
    forbidden = FORBIDDEN.search(text)
    if forbidden:
        raise ValueError(
            f"The summary uses '{forbidden.group(0)}'. It must describe signals for review, "
            "never assert fraud or suggest a decision."
        )
    given = " ".join(
        [str(request.score), *(f"{s.title} {s.explanation} {s.contribution}" for s in request.signals)]
    )
    invented = sorted(_numbers(text) - _numbers(given))
    if invented:
        raise ValueError(f"The summary uses numbers that are not in the signals: {', '.join(invented)}.")
    ordered = sorted(cited, key=lambda reference: int(reference[1:]))
    return text, ordered


class RiskSummarizer:
    def __init__(self, gateway: ModelGateway) -> None:
        self._gateway = gateway
        self._prompt = load_prompt("risk_summary", 2)

    async def summarize(self, request: ExplanationRequest) -> ExplanationResponse:
        runs: list[ModelRun] = []
        structured = StructuredRequest(
            task=Task.RISK_SUMMARY,
            prompt_version=self._prompt.version,
            schema_name="risk_summary",
            schema=SUMMARY_SCHEMA,
            messages=self._messages(request),
            parse=lambda content: checked(SummaryOutput.model_validate(content), request),
        )
        try:
            text, cited = await once_more(partial(self._gateway.complete, structured, runs))
        except ProcessingError as error:
            # The screening stands without a summary, so this is reported rather than raised.
            logger.warning("risk_summary_unavailable", reason=error.code)
            return ExplanationResponse(
                claim_id=request.claim_id,
                status=ExplanationStatus.UNAVAILABLE,
                text=None,
                cited=[],
                problem=error.code,
                prompt_version=self._prompt.version,
                runs=runs,
            )
        return ExplanationResponse(
            claim_id=request.claim_id,
            status=ExplanationStatus.AVAILABLE,
            text=text,
            cited=cited,
            problem=None,
            prompt_version=self._prompt.version,
            runs=runs,
        )

    def _messages(self, request: ExplanationRequest) -> list[Message]:
        lines = [
            f"band: {request.band}",
            f"score: {request.score} of 100",
            f"route: {request.route}",
            "",
        ]
        for signal in request.signals:
            severity = f", severity {signal.severity}" if signal.severity else ""
            lines.append(
                f"[{signal.id}] {signal.source.value}, {signal.kind.value}, {signal.category}{severity}, "
                f"contribution {signal.contribution}: {signal.title}. {signal.explanation}"
            )
        content = "<screening>\n" + "\n".join(lines) + "\n</screening>"
        return [{"role": "system", "content": self._prompt.text}, {"role": "user", "content": content}]
