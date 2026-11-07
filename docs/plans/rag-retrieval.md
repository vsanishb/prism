# RAG and Retrieval

## Purpose
Find the most relevant, contractually meaningful evidence for a claim, rather than just similar text.

## Scope
**In:** chunking, embeddings, hybrid search, filtering, ranking, relationship expansion, evaluation.
**Out:** reasoning over evidence (M6).

## Design considerations

**Chunking.** One provision per chunk, never split by a fixed size. Long provisions split on subclause boundaries and repeat their heading path. Each chunk's search text includes its context (for example section path and benefit name). The stored source text stays verbatim.

**Metadata.** `policy_version_id`, section path, clause ID, provision type, source pages, embedding model, and pipeline version.

**Retrieval steps** ([flow](../system-flows.md#rag-retrieval))

| Step | Approach |
|---|---|
| Filter | Exact `policy_version_id` is required at the repository layer, and optional type filters narrow further |
| Semantic | pgvector cosine distance, exact scan ([D-024](../decisions.md)) |
| Lexical | `tsvector` with weighted fields and `ts_rank_cd`; `pg_trgm` for clause IDs and near-matches ([D-022](../decisions.md)) |
| Fusion | Reciprocal rank fusion ([D-023](../decisions.md)) |
| Rerank | Cross-encoder over top candidates |
| Expand | Add related provisions through `provision_relations` |

- Clause numbers and defined terms in a query also trigger exact lookups.
- Search text uses the `simple` configuration alongside `english`, so that medical terms and codes are not stemmed incorrectly.
- If the reranker fails, return fused results marked `degraded`.
- Queries are built from de-identified facts ([security](../security.md#data-privacy)).
- `RetrievalResult` keeps per-stage ranks and scores, for the explorer and for audit.

**Embeddings.** Stored with their model name, as a 1536-dimension `vector`. Indexing happens at publication, since only `PUBLISHED` versions are searchable ([D-075](../decisions.md)). As built: [retrieval module](../modules/retrieval.md).

## Dependencies
M4 published provisions.

## Components
`Chunker`, `EmbeddingService`, `SemanticRetriever`, `LexicalRetriever`, `RankFusion`, `Reranker`, `RelationExpander`, `RetrievalPipeline`, evaluation harness, retrieval explorer screen.

## Testing
Isolation between policy versions; exact clause and term lookup; synonym matching; expansion completeness; reranker failure; recall against a labeled golden set, comparing semantic-only, lexical-only, and hybrid.

## Completion criteria
Targets in [testing.md](../testing.md#testing-ai-components) are met, with zero cross-version leaks, and measured results recorded to confirm or supersede D-023, D-024, and the embedding choice.
