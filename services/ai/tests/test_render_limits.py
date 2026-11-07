"""Untrusted files must fail cleanly and within limits. Malformed input is expected here, so the
only unacceptable outcomes are a hang, unbounded memory, or a crash of this service."""

import contextlib
import struct
import time
import zlib
from pathlib import Path

import pytest

from prism_ai.documents.render import RenderLimits, render_window
from prism_ai.gateway.errors import DocumentUnreadable

TEST_DATA = Path(__file__).parents[3] / "test-data"
TIME_BOUND_SECONDS = 20.0
PDF = "application/pdf"
PNG = "image/png"


def read(path: str) -> bytes:
    return (TEST_DATA / path).read_bytes()


def valid_pdf() -> bytes:
    return read("claims/appendectomy/hospital-bill.pdf")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))


def png_claiming_size(width: int, height: int) -> bytes:
    """A tiny file whose header claims an enormous image: a decompression bomb."""
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"IDAT", zlib.compress(b"\x00" * 1000))
        + png_chunk(b"IEND", b"")
    )


MALFORMED: dict[str, tuple[bytes, str]] = {
    "empty": (b"", PDF),
    "not a pdf": (b"this is not a pdf at all", PDF),
    "header only": (b"%PDF-1.7\n", PDF),
    "truncated": (read("invalid/truncated.pdf"), PDF),
    "cut in half": (valid_pdf()[: len(valid_pdf()) // 2], PDF),
    "password protected": (read("invalid/password-protected.pdf"), PDF),
    "bytes flipped": (bytes(byte ^ 0xFF for byte in valid_pdf()), PDF),
    "image bomb": (png_claiming_size(100_000, 100_000), PNG),
    "garbage image": (b"\x89PNG\r\n\x1a\n" + b"\xff" * 512, PNG),
    "garbage tiff": (b"II*\x00" + b"\xff" * 512, "image/tiff"),
    "garbage jpeg": (b"\xff\xd8\xff" + b"\x00" * 512, "image/jpeg"),
}


def circular_pdf() -> bytes:
    """Object references that loop back on themselves."""
    return (
        b"%PDF-1.7\n"
        b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        b"2 0 obj\n<< /Type /Pages /Kids [2 0 R] /Count 1000000 /Parent 2 0 R >>\nendobj\n"
        b"trailer\n<< /Root 1 0 R >>\n%%EOF\n"
    )


@pytest.mark.parametrize("name", sorted(MALFORMED))
def test_malformed_files_fail_cleanly_and_quickly(name: str) -> None:
    content, media_type = MALFORMED[name]
    started = time.monotonic()
    with pytest.raises(DocumentUnreadable):
        render_window(content, media_type, 1, 2)
    assert time.monotonic() - started < TIME_BOUND_SECONDS


def test_circular_page_trees_end_within_the_time_bound() -> None:
    """Failing and returning nothing are both acceptable. Not returning at all is not."""
    started = time.monotonic()
    with contextlib.suppress(DocumentUnreadable):
        render_window(circular_pdf(), PDF, 1, 1)
    assert time.monotonic() - started < TIME_BOUND_SECONDS


def test_a_render_over_the_time_limit_is_killed() -> None:
    started = time.monotonic()
    with pytest.raises(DocumentUnreadable, match="time limit"):
        render_window(valid_pdf(), PDF, 1, 1, RenderLimits(timeout_seconds=0.001))
    assert time.monotonic() - started < TIME_BOUND_SECONDS


def test_a_render_over_the_memory_limit_is_killed() -> None:
    with pytest.raises(DocumentUnreadable, match="memory limit"):
        render_window(valid_pdf(), PDF, 1, 1, RenderLimits(max_memory_bytes=1))


def test_a_valid_pdf_has_a_text_layer_with_word_boxes() -> None:
    window = render_window(valid_pdf(), PDF, 1, 8)
    assert window.page_count == 1
    page = window.pages[0]
    assert page.has_text_layer
    assert "1,06,000.00" in page.text
    assert page.image
    assert all(0 <= value <= 1 for word in page.words for value in word.box)


def test_a_scan_has_no_text_layer() -> None:
    page = render_window(read("claims/scanned/prescription.png"), PNG, 1, 1).pages[0]
    assert not page.has_text_layer
    assert page.image


def test_a_multi_page_tiff_is_windowed() -> None:
    content = read("claims/scanned/discharge-note.tiff")
    window = render_window(content, "image/tiff", 2, 2)
    assert window.page_count == 2
    assert [page.number for page in window.pages] == [2]


def test_a_window_past_the_end_is_simply_empty() -> None:
    window = render_window(valid_pdf(), PDF, 5, 8)
    assert window.page_count == 1
    assert window.pages == []


def test_a_watermark_alone_does_not_count_as_a_text_layer() -> None:
    assert render_window(read("claims/hard/dense-bill.pdf"), PDF, 2, 2).pages[0].has_text_layer
