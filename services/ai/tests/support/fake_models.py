"""Deterministic stand-ins for embedding and rerank models, for offline tests and the local stub.

Vectors are hashed bags of words, so that texts sharing words are close, and nothing else is.
That is enough to test the plumbing of semantic search, not its quality: quality is measured by
the evaluation set, on real models.
"""

import hashlib
import math
import re
from typing import Any

DIMENSIONS = 1536
WORD = re.compile(r"[a-z0-9]+")
STOPWORDS = frozenset({"a", "an", "and", "are", "as", "for", "in", "is", "of", "or", "the", "to", "with"})


def words(text: str) -> list[str]:
    return [word.rstrip("s") for word in WORD.findall(text.lower()) if word not in STOPWORDS]


def vector(text: str) -> list[float]:
    values = [0.0] * DIMENSIONS
    for word in words(text):
        digest = hashlib.sha256(word.encode()).digest()
        index = int.from_bytes(digest[:4], "big") % DIMENSIONS
        values[index] += 1.0 if digest[4] % 2 else -1.0
    norm = math.sqrt(sum(value * value for value in values)) or 1.0
    # A text with no words still gets a valid, non-zero vector.
    return [value / norm for value in values] if any(values) else [1.0 / math.sqrt(DIMENSIONS)] * DIMENSIONS


def overlap(query: str, document: str) -> float:
    wanted = set(words(query))
    return len(wanted & set(words(document))) / len(wanted) if wanted else 0.0


def embeddings_reply(body: dict[str, Any]) -> dict[str, Any]:
    return {
        "provider": "Stub",
        "data": [{"index": index, "embedding": vector(text)} for index, text in enumerate(body["input"])],
        "usage": {"prompt_tokens": sum(len(words(text)) for text in body["input"]), "cost": 0.00001},
    }


def rerank_reply(body: dict[str, Any]) -> dict[str, Any]:
    scores = [overlap(body["query"], document) for document in body["documents"]]
    order = sorted(range(len(scores)), key=lambda index: -scores[index])
    return {
        "provider": "Stub",
        "results": [{"index": index, "relevance_score": scores[index]} for index in order],
        "usage": {"total_tokens": 100, "cost": 0.00002},
    }
