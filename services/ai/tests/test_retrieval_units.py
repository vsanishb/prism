"""Chunking and rank fusion: pure functions, tested without a database or a model."""

from uuid import UUID, uuid4

from prism_ai.policies.schemas import ProvisionType
from prism_ai.retrieval.chunker import MAX_CHUNK_CHARS, chunk, split_text
from prism_ai.retrieval.fusion import Hit, Ranked, by_provision, fuse
from prism_ai.retrieval.schemas import IndexProvision


def provision(
    key: str | None, title: str, text: str, parent: str | None = None, **kind: ProvisionType
) -> IndexProvision:
    return IndexProvision(
        id=uuid4(),
        key=key,
        type=kind.get("type", ProvisionType.CLAUSE),
        title=title,
        text=text,
        page_start=1,
        page_end=1,
        parent_key=parent,
    )


def test_a_provision_that_fits_is_one_chunk_with_its_context_in_the_heading() -> None:
    section = provision("4", "Exclusions", "The following are not covered.")
    clause = provision(
        "4.4", "Dental treatment", "Dental treatment, except after an accident.", type=ProvisionType.EXCLUSION
    )

    chunks, paths = chunk([section, clause])

    dental = [item for item in chunks if item.provision_id == clause.id]
    assert len(dental) == 1
    assert dental[0].body == clause.text
    assert dental[0].heading == "4 Exclusions > 4.4 Dental treatment (Exclusion)"
    assert paths[clause.id] == ["4 Exclusions"]


def test_a_stated_parent_wins_over_the_clause_number() -> None:
    schedule = provision("S", "Schedule", "Schedule of benefits.")
    row = provision("2.6", "Cataract surgery", "Up to INR 40,000 per eye.", parent="S")
    _, paths = chunk([schedule, row, provision("2", "Benefits", "Benefits.")])
    assert paths[row.id] == ["S Schedule"]


def test_a_parent_cycle_does_not_loop() -> None:
    first = provision("1", "One", "Text.", parent="2")
    second = provision("2", "Two", "Text.", parent="1")
    _, paths = chunk([first, second])
    assert paths[first.id] == ["2 Two"]


def test_a_long_provision_splits_at_subclauses_and_every_part_is_verbatim() -> None:
    items = [
        f"({letter}) {'Treatment of a listed condition that is not covered. ' * 9}" for letter in "abcdef"
    ]
    text = "The following are excluded: " + " ".join(items)
    assert len(text) > MAX_CHUNK_CHARS

    parts = split_text(text)

    assert len(parts) > 1
    assert all(len(part) <= MAX_CHUNK_CHARS for part in parts)
    assert all(part in text for part in parts)
    # Every subclause sits whole inside one part: none is cut.
    for item in items:
        assert sum(item.strip() in part for part in parts) == 1


def test_without_subclauses_a_long_provision_splits_between_sentences_never_inside_one() -> None:
    sentences = [
        f"Sentence number {index} explains a condition of cover in some detail." for index in range(60)
    ]
    text = " ".join(sentences)

    parts = split_text(text)

    assert len(parts) > 1
    assert all(part.endswith(".") for part in parts)
    assert " ".join(parts) == text


def test_one_sentence_longer_than_the_limit_stays_whole() -> None:
    text = "word " * 500 + "end."
    assert split_text(text.strip()) == [text.strip()]


def test_by_provision_keeps_each_provision_once_at_its_best_chunk() -> None:
    first, second = uuid4(), uuid4()
    chunk_a, chunk_b, chunk_c = uuid4(), uuid4(), uuid4()

    ranked = by_provision([Hit(chunk_a, first, 0.9), Hit(chunk_b, first, 0.8), Hit(chunk_c, second, 0.7)])

    assert [(entry.provision_id, entry.rank, entry.chunk_id) for entry in ranked] == [
        (first, 1, chunk_a),
        (second, 2, chunk_c),
    ]


def ranked(*provisions: UUID) -> list[Ranked]:
    return [Ranked(provision, rank, 1.0, provision) for rank, provision in enumerate(provisions, 1)]


def test_fusion_prefers_a_provision_found_by_two_retrievers() -> None:
    both, semantic_only, lexical_only = uuid4(), uuid4(), uuid4()

    fused = fuse({"semantic": ranked(semantic_only, both), "lexical": ranked(lexical_only, both)})

    assert fused[0].provision_id == both
    assert [entry.rank for entry in fused] == [1, 2, 3]


def test_fusion_is_deterministic_on_ties() -> None:
    first, second = uuid4(), uuid4()
    runs = {
        tuple(entry.provision_id for entry in fuse({"a": ranked(first), "b": ranked(second)}))
        for _ in range(5)
    }
    assert len(runs) == 1


def test_fusion_keeps_the_chunk_from_the_list_that_ranked_the_provision_highest() -> None:
    provision_id, low_chunk, high_chunk = uuid4(), uuid4(), uuid4()
    other = uuid4()
    semantic = [Ranked(other, 1, 1.0, other), Ranked(provision_id, 2, 1.0, low_chunk)]
    lexical = [Ranked(provision_id, 1, 1.0, high_chunk)]

    fused = fuse({"semantic": semantic, "lexical": lexical})

    assert next(entry for entry in fused if entry.provision_id == provision_id).chunk_id == high_chunk
