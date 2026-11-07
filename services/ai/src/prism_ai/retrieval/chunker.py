"""Structure-aware chunking: one provision per chunk, never cut at a fixed size.

A long provision is split at its subclauses, such as (a) and (b), and failing that between
sentences, never inside one. Every chunk repeats its heading, so that a part read alone still says
which clause it belongs to. Bodies are verbatim slices of the provision text.
"""

import itertools
import re
from dataclasses import dataclass
from uuid import UUID

from prism_ai.policies.schemas import ProvisionType
from prism_ai.retrieval.schemas import IndexProvision

# About 450 tokens: long enough for any ordinary clause, short enough that one subclause is not
# drowned out by its neighbours in an embedding.
MAX_CHUNK_CHARS = 1800

TYPE_LABELS = {
    ProvisionType.SCHEDULE: "Schedule",
    ProvisionType.DEFINITION: "Definition",
    ProvisionType.BENEFIT: "Benefit",
    ProvisionType.WAITING_PERIOD: "Waiting period",
    ProvisionType.EXCLUSION: "Exclusion",
    ProvisionType.PROCEDURE: "Claim procedure",
    ProvisionType.CLAUSE: "Clause",
}

SUBCLAUSE = re.compile(r"(?:(?<=\s)|^)\((?:[a-z]|[ivx]{1,5}|\d{1,2})\)\s")
SENTENCE_END = re.compile(r"(?<=[.;:])\s+(?=\S)")


@dataclass(frozen=True)
class Chunk:
    provision_id: UUID
    part: int
    parts: int
    heading: str
    body: str

    def search_text(self) -> str:
        """What is embedded and reranked: the context first, then the verbatim text."""
        return f"{self.heading}\n{self.body}"


def section_path(provision: IndexProvision, by_key: dict[str, IndexProvision]) -> list[str]:
    """Titles of the provisions that contain this one, outermost first. Uses the stated parent, or
    else the clause number's prefix: 4.5 sits in 4, where a provision 4 exists."""
    path: list[str] = []
    seen = {provision.key} if provision.key else set()
    current = provision
    while True:
        parent_key = current.parent_key or _numeric_parent(current.key)
        if parent_key is None or parent_key in seen or parent_key not in by_key:
            return path
        seen.add(parent_key)
        current = by_key[parent_key]
        path.insert(0, label(current))


def _numeric_parent(key: str | None) -> str | None:
    if key is None or "." not in key:
        return None
    return key.rsplit(".", 1)[0]


def label(provision: IndexProvision) -> str:
    title = provision.title.strip()
    # A title may already start with its clause number, as in "1.3 Pre-existing Disease".
    if provision.key and not re.match(rf"{re.escape(provision.key)}(?![\w.])", title):
        return f"{provision.key} {title}".strip()
    return title or (provision.key or "")


def heading(provision: IndexProvision, path: list[str]) -> str:
    return " > ".join([*path, label(provision)]) + f" ({TYPE_LABELS[provision.type]})"


def split_text(text: str, limit: int = MAX_CHUNK_CHARS) -> list[str]:
    """Verbatim parts of `text`, each within `limit` unless one sentence alone is longer."""
    if len(text) <= limit:
        return [text]
    cuts = [match.start() for match in SUBCLAUSE.finditer(text) if match.start() > 0]
    segments = _slices(text, cuts)
    pieces: list[str] = []
    for segment in segments:
        if len(segment) <= limit:
            pieces.append(segment)
        else:
            pieces += _slices(segment, [match.end() for match in SENTENCE_END.finditer(segment)])
    return [part.strip() for part in _pack(pieces, limit) if part.strip()]


def _slices(text: str, cuts: list[int]) -> list[str]:
    bounds = [0, *cuts, len(text)]
    return [text[start:end] for start, end in itertools.pairwise(bounds) if end > start]


def _pack(pieces: list[str], limit: int) -> list[str]:
    """Joins neighbouring pieces while they fit, so that short subclauses share a chunk."""
    packed: list[str] = []
    for piece in pieces:
        if packed and len(packed[-1]) + len(piece) <= limit:
            packed[-1] += piece
        else:
            packed.append(piece)
    return packed


def chunk(provisions: list[IndexProvision]) -> tuple[list[Chunk], dict[UUID, list[str]]]:
    """Chunks for every provision, and each provision's section path."""
    by_key = {provision.key: provision for provision in provisions if provision.key}
    chunks: list[Chunk] = []
    paths: dict[UUID, list[str]] = {}
    for provision in provisions:
        path = section_path(provision, by_key)
        paths[provision.id] = path
        title = heading(provision, path)
        bodies = split_text(provision.text)
        for part, body in enumerate(bodies, start=1):
            chunks.append(Chunk(provision.id, part, len(bodies), title, body))
    return chunks, paths
