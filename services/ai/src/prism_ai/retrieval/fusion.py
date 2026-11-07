"""Reciprocal rank fusion (D-023). Pure, so that ranking is tested without a database or a model."""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from uuid import UUID

# The usual constant. It damps the gap between first and second place, so that a provision found
# by two retrievers outranks one found first by only one.
RRF_K = 60


@dataclass(frozen=True)
class Hit:
    """A chunk found by one retriever. Lists are ordered best first."""

    chunk_id: UUID
    provision_id: UUID
    score: float


@dataclass(frozen=True)
class Ranked:
    provision_id: UUID
    rank: int
    score: float
    chunk_id: UUID


def by_provision(hits: Sequence[Hit]) -> list[Ranked]:
    """One entry per provision, at the rank of its best chunk. Results are provisions, not parts."""
    ranked: list[Ranked] = []
    seen: set[UUID] = set()
    for hit in hits:
        if hit.provision_id in seen:
            continue
        seen.add(hit.provision_id)
        ranked.append(Ranked(hit.provision_id, len(ranked) + 1, hit.score, hit.chunk_id))
    return ranked


def fuse(lists: Mapping[str, Sequence[Ranked]], k: int = RRF_K) -> list[Ranked]:
    """Fused order, best first. Each provision keeps the chunk from the list that ranked it highest.
    Ties break on the provision ID, so that the order is the same on every run."""
    scores: dict[UUID, float] = {}
    best: dict[UUID, tuple[int, UUID]] = {}
    for ranking in lists.values():
        for entry in ranking:
            scores[entry.provision_id] = scores.get(entry.provision_id, 0.0) + 1.0 / (k + entry.rank)
            current = best.get(entry.provision_id)
            if current is None or entry.rank < current[0]:
                best[entry.provision_id] = (entry.rank, entry.chunk_id)
    order = sorted(scores, key=lambda provision: (-scores[provision], str(provision)))
    return [
        Ranked(provision, rank, scores[provision], best[provision][1])
        for rank, provision in enumerate(order, 1)
    ]
