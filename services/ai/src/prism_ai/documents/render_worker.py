"""Opens one untrusted document and renders a window of pages, in its own process.

Native parsers can hang or exhaust memory on hostile files, and a thread could not be stopped. The
parent (`render.py`) kills this process at its time and memory limits. It reads the file on stdin
and writes JSON on stdout, so that document content never touches the disk here.

    python -m prism_ai.documents.render_worker <media_type> <first_page> <last_page> <max_edge_px>
"""

import base64
import io
import json
import sys
from typing import Any

import pypdfium2 as pdfium
import pypdfium2.raw as pdfium_raw
from PIL import Image, ImageSequence

JPEG_QUALITY = 82
MAX_IMAGE_PIXELS = 60_000_000
# Below this, a page's "text layer" is a watermark or stray marks rather than its content.
MIN_TEXT_LAYER_CHARS = 40


def encode(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, "JPEG", quality=JPEG_QUALITY)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def visible_point(
    x: float, y: float, bbox: tuple[float, float, float, float], rotation: int
) -> tuple[float, float]:
    """Maps PDF page space to a 0-1 position on the page as displayed, with the origin at top left."""
    left, bottom, right, top = bbox
    u = (x - left) / (right - left)
    v = (top - y) / (top - bottom)
    match rotation:
        case 90:
            return 1 - v, u
        case 180:
            return 1 - u, 1 - v
        case 270:
            return v, 1 - u
        case _:
            return u, v


def words_on(page: pdfium.PdfPage) -> tuple[str, list[list[Any]]]:
    """Returns the page text and its words, each with a normalized box."""
    text_page = page.get_textpage()
    bbox = page.get_bbox()
    rotation = page.get_rotation()
    words: list[list[Any]] = []
    current: list[str] = []
    box: list[float] = []

    def flush() -> None:
        if current:
            words.append(["".join(current), *(round(value, 5) for value in box)])
            current.clear()
            box.clear()

    for index in range(text_page.count_chars()):
        code = pdfium_raw.FPDFText_GetUnicode(text_page.raw, index)
        character = chr(code) if 0 < code < 0x110000 else " "
        if character.isspace():
            flush()
            continue
        left, bottom, right, top = text_page.get_charbox(index)
        x0, y0 = visible_point(left, top, bbox, rotation)
        x1, y1 = visible_point(right, bottom, bbox, rotation)
        low_x, high_x, low_y, high_y = min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1)
        box[:] = (
            [min(box[0], low_x), min(box[1], low_y), max(box[2], high_x), max(box[3], high_y)]
            if box
            else [low_x, low_y, high_x, high_y]
        )
        current.append(character)
    flush()
    text = " ".join(word[0] for word in words)
    return text, words


def render_pdf(data: bytes, first: int, last: int, max_edge: int) -> dict[str, Any]:
    document = pdfium.PdfDocument(data)
    count = len(document)
    pages = []
    for number in range(first, min(last, count) + 1):
        page = document[number - 1]
        width, height = page.get_size()
        scale = min(max_edge / max(width, height), 4.0)
        text, words = words_on(page)
        has_text = sum(len(word[0]) for word in words) >= MIN_TEXT_LAYER_CHARS
        pages.append(
            {
                "number": number,
                "image": encode(page.render(scale=scale).to_pil()),
                "text": text if has_text else "",
                "words": words if has_text else [],
            }
        )
    return {"pageCount": count, "pages": pages}


def render_image(data: bytes, first: int, last: int, max_edge: int) -> dict[str, Any]:
    Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS
    with Image.open(io.BytesIO(data)) as source:
        count = getattr(source, "n_frames", 1)
        pages = []
        for number, frame in enumerate(ImageSequence.Iterator(source), start=1):
            if number > last:
                break
            if number >= first:
                copy = frame.convert("RGB")
                copy.thumbnail((max_edge, max_edge))
                pages.append({"number": number, "image": encode(copy), "text": "", "words": []})
    return {"pageCount": count, "pages": pages}


def main() -> None:
    media_type, first, last, max_edge = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    data = sys.stdin.buffer.read()
    render = render_pdf if media_type == "application/pdf" else render_image
    sys.stdout.write(json.dumps(render(data, first, last, max_edge)))


if __name__ == "__main__":
    main()
