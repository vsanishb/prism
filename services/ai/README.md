# AI service

FastAPI service for AI and specialized processing. **Not yet implemented** (scaffolded in Milestone 1).

Stateless with respect to claims: it receives inputs from the core service and returns structured results. It persists only policy knowledge, in the `knowledge` schema. It accepts only signed requests from the core service and never calls it back.

Planned packages under `prism_ai`:

| Package | Responsibility |
|---|---|
| `api` | Internal routes and service authentication |
| `gateway` | The only path to OpenRouter: model routing, privacy enforcement, retries, usage |
| `prompts` | Versioned prompts |
| `documents` | Page rendering, text-layer analysis, vision extraction, grounding |
| `policy_knowledge` | Provision extraction, validation, chunking, embedding |
| `retrieval` | Semantic, lexical, and metadata retrieval; fusion; reranking |
| `policy_agent` | Bounded evidence-based analysis and citation validation |
| `risk` | Rules, consistency analysis, and explainable scoring |

See [architecture](../../docs/architecture.md).
