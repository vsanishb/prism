# Retrieval

Hybrid search over published policy versions. Core: `dev.prism.core.retrieval`. AI: `services/ai/src/prism_ai/retrieval`. Design: [plan](../plans/rag-retrieval.md), [D-075](../decisions.md) to [D-078](../decisions.md).

## Flow

1. Publishing a version writes `POLICY_VERSION_PUBLISHED` to the outbox, in the publication's transaction.
2. `PolicyIndexingWorkflow` marks the index `INDEXING` and sends the published provisions and resolved relations to the AI service.
3. The AI service chunks and embeds them, then replaces the version's index in one transaction.
4. Core records the index as `READY`, with its chunk count, embedding model, and model usage, and audits it.
5. A search goes from the explorer to core, which checks the version and index, calls the AI service, and checks every provision in the answer belongs to that version.

A failed index shows **Indexing failed** and waits for a policy manager's retry.

## Chunking

One provision per chunk. A provision over 1,800 characters is split at its subclauses, such as (a) and (b), and failing that between sentences, never inside one. Every chunk carries a heading with its section path, clause, title, and type. The body is the provision text, verbatim.

## Search

| Stage | How |
|---|---|
| Filter | The version ID, in every SQL query, and optionally provision types |
| Semantic | Exact cosine distance with pgvector, top 30 chunks |
| Lexical | Full text, any word, stemmed with `english`; tokens with digits also as written with `simple`; `ts_rank_cd`, top 30 |
| Exact | Clauses named as clauses ("clause 4.5"); defined terms within trigram word similarity 0.6 |
| Fusion | RRF, k = 60, over provisions, each at its best chunk |
| Rerank | `qwen/qwen3-reranker-8b` over the top 20. A named clause stays first |
| Expansion | Related provisions, one hop both ways: exceptions, conditions, limits, definitions, references |

A failed query embedding or rerank is tried once more, then skipped, and the response lists it in `degraded`. A query is embedded with the model that built the index.

## Data

`knowledge` schema, owned by the AI service: `search_indexes`, `indexed_provisions`, `indexed_relations`, and `chunks`, with a stored `tsvector` and a `vector(1536)`. IDs are core's provision IDs, with no foreign key across schemas.

`core.policy_search_indexes` holds each version's index status. `core.ai_runs` records indexing and search usage against the version. Neither can be deleted by the runtime role.

## Endpoints

Under `/api/v1/policies/{policyId}/versions/{versionId}`. Reading and searching need `POLICY_READ`, and a retry needs `POLICY_MANAGE`.

| Route | Purpose |
|---|---|
| `GET /search-index` | `NOT_PUBLISHED`, `QUEUED`, `INDEXING`, `READY`, or `FAILED`, with details |
| `POST /search-index/retry` | Retry a failed index |
| `POST /search` | `{query, types}`. Results with every stage's rank and score, source locations, and related provisions |

The AI service's internal routes are `POST /internal/v1/policies/index` and `POST /internal/v1/policies/search`.

## Screens

- The version page shows the index status, and **Search this version** once it is ready.
- The explorer, at `/policies/{id}/versions/{versionId}/search`: results with each stage's rank, a comparison of the stages' rankings, related provisions, and the source page with the provision outlined.

## Evaluation

Golden set: `services/ai/evals/retrieval_corpus.json` (four synthetic wordings, 127 provisions, with Gold v1 and v2 nearly identical) and `retrieval_queries.json` (50 labelled questions: clause numbers, defined terms, the wording's words, paraphrases, and claim-like facts).

```bash
python -m uv run --project services/ai python services/ai/evals/run_retrieval.py
```

On 2026-11-07, with `openai/text-embedding-3-small` and `qwen/qwen3-reranker-8b`: recall@10 of 100% final, 96.6% fused, 98.3% semantic only, 84.7% lexical only; 0 results from another version; 6 of 6 related provisions; median search 1.3 s; $0.03 for the run. Comparison of embedding models in [D-077](../decisions.md).

## Not built

- Queries from claim facts, and their de-identification: M6, with the Policy Agent.
- Indexes are not rebuilt when the embedding model changes. That needs a rebuild command when it first happens.
- Accuracy on real wordings, which are longer and less tidy than the synthetic set.
