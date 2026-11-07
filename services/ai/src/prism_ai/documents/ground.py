"""Finds a model's quote in the page's real text. Model-reported coordinates are unreliable, so a
region comes only from a match against the text layer."""

import re
import unicodedata
from dataclasses import dataclass

from prism_ai.documents.render import RenderedPage, Word

TOKEN = re.compile(r"[^\W_]+", re.UNICODE)


def tokens(text: str) -> list[str]:
    """Letters and digits only, so that spacing and punctuation differences do not matter."""
    return TOKEN.findall(unicodedata.normalize("NFKC", text).casefold())


@dataclass(frozen=True)
class Match:
    region: tuple[float, float, float, float]


def _indexed_tokens(words: list[Word]) -> list[tuple[str, int]]:
    return [(token, index) for index, word in enumerate(words) for token in tokens(word.text)]


def locate_quote(quote: str, page: RenderedPage) -> Match | None:
    """Returns the box around the quote's first occurrence on the page, or None if it is not there."""
    wanted = tokens(quote)
    if not wanted:
        return None
    indexed = _indexed_tokens(page.words)
    span = len(wanted)
    for start in range(len(indexed) - span + 1):
        if all(indexed[start + offset][0] == wanted[offset] for offset in range(span)):
            matched = page.words[indexed[start][1] : indexed[start + span - 1][1] + 1]
            return Match(
                region=(
                    min(word.box[0] for word in matched),
                    min(word.box[1] for word in matched),
                    max(word.box[2] for word in matched),
                    max(word.box[3] for word in matched),
                )
            )
    return None


def contains_tokens(text: str, part: str) -> bool:
    """True if `part` appears in `text` as a whole run of words."""
    source, wanted = tokens(text), tokens(part)
    if not wanted:
        return False
    span = len(wanted)
    return any(source[start : start + span] == wanted for start in range(len(source) - span + 1))


def value_in_quote(raw_value: str, quote: str) -> bool:
    """A value must come from its own quote, otherwise the citation does not support it."""
    return contains_tokens(quote, raw_value)
