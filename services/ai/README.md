# AI service

FastAPI service for model work and the policy search index. It accepts only signed requests from the core service, never calls it back, and keeps no claim data: it receives what it needs and returns a result. The only data it stores is the search index of published policy wordings, in the `knowledge` schema.

Run it with `scripts/prism.py run-ai`. Test it with `python -m uv run pytest`, and check it with `ruff` and `mypy --strict`. See the [repository README](../../README.md#getting-started).

Packages under `prism_ai`:

| Package | Responsibility |
|---|---|
| `api` | Internal routes and service authentication |
| `gateway` | The only path to models: routing, zero-retention enforcement, output validation, usage records |
| `documents` | Rendering in an isolated process, extraction, and grounding in the text layer |
| `policies` | Reading policy wordings into provisions |
| `retrieval` | Chunking, embeddings, hybrid search, fusion, and reranking |
| `agent` | The policy agent and its citation checks |
| `risk` | Synthetic baseline, Isolation Forest and LOF, and the checked risk summary |
| `prompts` | Versioned prompts |

`evals/` holds evaluation sets and scripts that measure each component against known answers, with real models. See the [module guides](../../docs/modules/README.md).
