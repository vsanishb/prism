"""Runs the render worker under time and memory limits."""

import json
import subprocess
import sys
import threading
from dataclasses import dataclass

import psutil
import structlog
from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from prism_ai.gateway.errors import DocumentUnreadable

logger = structlog.get_logger()

MEMORY_POLL_SECONDS = 0.05


class Word(BaseModel):
    """A word and its box, as fractions of the page with the origin at top left."""

    model_config = ConfigDict(frozen=True)

    text: str
    box: tuple[float, float, float, float]


class RenderedPage(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    number: int
    image: str
    text: str
    words: list[Word]

    @property
    def has_text_layer(self) -> bool:
        return bool(self.words)


class RenderedWindow(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    page_count: int
    pages: list[RenderedPage]


@dataclass(frozen=True)
class RenderLimits:
    timeout_seconds: float = 60.0
    max_memory_bytes: int = 1_500_000_000
    max_edge_pixels: int = 1600


def _watch_memory(process: subprocess.Popen[bytes], limit: int, exceeded: threading.Event) -> None:
    try:
        watched = psutil.Process(process.pid)
        while process.poll() is None:
            if watched.memory_info().rss > limit:
                exceeded.set()
                process.kill()
                return
            threading.Event().wait(MEMORY_POLL_SECONDS)
    except psutil.Error:
        return  # The process ended between checks.


def render_window(
    content: bytes, media_type: str, first: int, last: int, limits: RenderLimits | None = None
) -> RenderedWindow:
    """Blocking, so callers use a thread. Raises DocumentUnreadable for any file that fails or
    exceeds a limit: retrying cannot help, and the reason is logged without any content."""
    bounds = limits or RenderLimits()
    command = [
        sys.executable,
        "-I",
        "-m",
        "prism_ai.documents.render_worker",
        media_type,
        str(first),
        str(last),
        str(bounds.max_edge_pixels),
    ]
    process = subprocess.Popen(  # noqa: S603 - fixed module, validated media type, integer arguments
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    exceeded = threading.Event()
    watcher = threading.Thread(
        target=_watch_memory, args=(process, bounds.max_memory_bytes, exceeded), daemon=True
    )
    watcher.start()
    try:
        output, _ = process.communicate(content, timeout=bounds.timeout_seconds)
    except subprocess.TimeoutExpired as error:
        process.kill()
        process.communicate()
        logger.warning("document_render_timeout", media_type=media_type)
        raise DocumentUnreadable("Rendering exceeded the time limit") from error
    if exceeded.is_set():
        logger.warning("document_render_memory_limit", media_type=media_type)
        raise DocumentUnreadable("Rendering exceeded the memory limit")
    if process.returncode != 0:
        logger.warning("document_render_failed", media_type=media_type, exit_code=process.returncode)
        raise DocumentUnreadable("The file could not be opened")
    try:
        raw = json.loads(output)
        raw["pages"] = [
            {**page, "words": [{"text": word[0], "box": word[1:5]} for word in page["words"]]}
            for page in raw["pages"]
        ]
        return RenderedWindow.model_validate(raw)
    except (ValueError, KeyError, TypeError, ValidationError) as error:
        raise DocumentUnreadable("The renderer returned unusable output") from error
