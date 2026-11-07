"""Failures the pipeline reports to core. `retryable` decides whether Temporal tries again."""

from prism_ai.gateway.usage import ModelRun


class ProcessingError(Exception):
    """Carries model runs already made, so that their usage is recorded even when processing fails."""

    code = "processing_failed"
    retryable = False
    status_code = 422

    def __init__(self, message: str, runs: list[ModelRun] | None = None) -> None:
        super().__init__(message)
        self.runs = runs or []


class ModelUnavailable(ProcessingError):
    """A rate limit, outage, or timeout. It should pass with time."""

    code = "model_unavailable"
    retryable = True
    status_code = 503


class ModelRejected(ProcessingError):
    """The provider refused the request itself: bad credentials, no credit, or an invalid request."""

    code = "model_rejected"
    status_code = 502


class ModelOutputInvalid(ProcessingError):
    """Output stayed invalid after repair and escalation. The document is flagged, never guessed."""

    code = "model_output_invalid"


class RouteNotPermitted(ProcessingError):
    """The route cannot be proven to meet the retention rules, so nothing is sent."""

    code = "route_not_permitted"
    status_code = 502


class DocumentUnreadable(ProcessingError):
    """The file could not be opened within the resource limits."""

    code = "document_unreadable"
