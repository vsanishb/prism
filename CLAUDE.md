# Prism — Development Standards

Prism is an AI-assisted Health Insurance Claims Intelligence Platform. These standards apply to all work in this repository.

**Start here:** [docs/README.md](docs/README.md) maps all documentation. Recover project context from [docs/project-context.md](docs/project-context.md). Check real state in [docs/progress.md](docs/progress.md), never from memory or from plans.

## Non-negotiable product rules

1. AI never makes a final claim decision. Humans hold authority over consequential outcomes.
2. A fraud signal is not a fraud determination. Never label a claim fraudulent, and never auto-reject.
3. Source documents are authoritative. Extractions and assessments are derived data.
4. Never invent missing data. Values are present, missing, uncertain, or conflicting, and the state is stored explicitly.
5. Every significant AI output traces to a source document, page, or policy provision.
6. Money, dates, and limits are computed by deterministic code, never by a model.
7. Claim-derived text goes only to zero-data-retention model endpoints ([D-027](docs/decisions.md)).

## Workflow

**Before a significant change**
1. Read the relevant plan in `docs/plans/` and module doc in `docs/modules/`.
2. Check [roadmap.md](docs/roadmap.md) and [progress.md](docs/progress.md).
3. Read the existing code and understand its design.
4. Identify affected modules, plus security and compatibility implications.

**After meaningful work**
1. Test it, and verify the frontend flow where applicable.
2. Update the module doc, roadmap checklist, and `progress.md`.
3. Record significant decisions in `decisions.md`; update `architecture.md` or `system-flows.md` if the design changed.
4. Commit a coherent, working change.

Never mark something complete unless it is implemented and tested. Never describe planned work as existing. When unsure, inspect the code.

## Milestones

- Work one milestone at a time, in roadmap order.
- A milestone is complete only when its flow works through the frontend and its tests pass.
- Do not build ahead of the current milestone, or leave placeholder screens presented as working features.

## Code quality

- Fix root causes. No quick workarounds, patch-on-patch fixes, or temporary code in production paths.
- Keep modules cohesive, with clear boundaries. Prefer small focused files; split before a file becomes monolithic.
- Remove dead code and duplication. Do not add abstractions without a present need.
- No hardcoded configuration. Validate configuration at startup and fail fast.
- Consider edge cases, invalid input, failure paths, concurrency, retries and idempotency, data integrity, and observability.
- Validate at trust boundaries. Handle errors deliberately: never swallow them, and never leak internals to clients.

### Comments

Explain why, not what: non-obvious reasoning, architectural constraints, security considerations, complex business rules. Do not narrate obvious code. Prefer clearer names over comments.

### Stack conventions

| Area | Conventions |
|---|---|
| Java (`services/core`) | Package by feature under `dev.prism.core.<module>`. Modules talk through public APIs; ArchUnit enforces this. Constructor injection. DTOs at the API edge; never expose entities. Temporal workflow code must stay deterministic. |
| Python (`services/ai`) | Fully typed, `mypy --strict`, `ruff`. Pydantic models at every boundary. All model calls go through the model gateway. Prompts are versioned files. Async I/O. |
| Next.js (`web`) | Server Components by default. Strict TypeScript, no `any`. Use design tokens and shared components, not one-off styles. Handle loading, empty, and error states on every screen. |
| Database | Schema changes only through Flyway migrations in `database/migrations`. Applied migrations are immutable. Money is `numeric` plus a currency code; never floating point. |
| APIs | Versioned paths, RFC 9457 problem responses, cursor pagination, idempotency keys on retryable writes. |

## Security

Security is part of every change. See [docs/security.md](docs/security.md).

- Never hardcode or commit secrets. Do not invent credentials or usable placeholders. If a required secret is missing, ask the user.
- Enforce authorization on the server, at the backend boundary. Frontend checks are presentation only.
- Audit sensitive actions in the same transaction as the change.
- Never log secrets, document content, or personal or health data. Log IDs.
- Use synthetic data only in development, tests, and fixtures. Real claim data is prohibited until the privacy gate in `security.md` is cleared.

## Testing

See [docs/testing.md](docs/testing.md). New behavior needs tests at the right level, and bug fixes need a regression test. Test AI components with schema validation, known inputs, failure handling, and uncertainty handling. Compiling is not completion. Report test results accurately, including failures.

## Versions and dependencies

- Pinned versions and their verification status live in [docs/decisions.md](docs/decisions.md#version-baseline). Do not upgrade casually.
- Before adding or upgrading a major dependency, verify compatibility with related components and record the decision.
- Prefer mature, well-supported libraries. Do not add one when a few lines of clear code will do.

## Documentation

- Be concise and structured. Give each file one purpose, and link rather than duplicate.
- Keep documentation synchronized with the code, and write about what is true now.
- Do not rewrite decision history: add a new entry that supersedes the old one.

## Git

- Commit each coherent working change, with focused commits and clear imperative messages.
- Include related documentation updates with the implementation they describe.
- Never commit broken or experimental code as completed work, and never commit secrets.
- Do not mix unrelated refactors with features.
