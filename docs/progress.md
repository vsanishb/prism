# Progress

The factual record of project state. Update after meaningful work. Newest entries go first in the log.

**Status terms:** `Planned` · `In progress` · `Implemented` (code exists) · `Tested` (verified at the required levels) · `Deferred`

## Current state

**Phase:** M1 complete, except for its first CI run. M2 is next.
**Runs on:** local PostgreSQL 18.3. Neon has not been connected.

| Capability | Status | Milestone |
|---|---|---|
| Documentation foundation | Implemented | M0 |
| CI pipeline | Implemented, never run | M1 |
| MFA, key rotation, self-service password change | Deferred | M9 |
| Authentication, RBAC, audit | Tested | M1 |
| Service-to-service authentication | Tested | M1 |
| Design system and app shell | Tested | M1 |
| Claims, policies, documents | Planned | M2 |
| Temporal workflows | Planned | M3 |
| Document intelligence | Planned | M3 |
| Policy intelligence | Planned | M4 |
| Hybrid retrieval | Planned | M5 |
| Policy Agent | Planned | M6 |
| Fraud and risk | Planned | M7 |
| Human review | Planned | M8 |
| Production readiness | Planned | M9 |

## Open items

| Item | Owner | Needed by |
|---|---|---|
| Create a Git remote, then confirm the first CI run | User | Before relying on CI |
| Provide Neon connection details, then run migrations there | User | Before shared use |
| Repair Docker Desktop: delete the stale `dockerInference` socket, which may need a restart or admin rights | User | M3, if Temporal runs in Docker |
| Provide OpenRouter key, set account to ZDR only | User | M3 |
| Make pgvector available: local build, container, or Neon ([D-041](decisions.md)) | Dev and user | M5 |

## Log

### 2026-11-06: M1 Secure platform

**Implemented:** core service (identity, RBAC, audit, AI client, system status), AI service with service authentication, Flyway migrations with least-privilege roles, local tooling, design system, and all M1 screens.

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL) | 86 passed |
| AI (pytest, real PostgreSQL for database tests) | 22 passed. `ruff` and `mypy --strict` clean |
| Web unit (Vitest) | 4 passed. ESLint and `tsc` clean |
| End-to-end, production build (Playwright) | 14 passed |
| End-to-end, dev server | 13 passed. CSP test excluded, as dev mode needs `unsafe-eval` |

Also verified on the running stack with a scripted browser check: stopping the AI service shows 3 failing checks, and restarting it recovers. Screenshots were reviewed at desktop width, and the audit page at phone width, before a final round of small styling fixes that was not re-reviewed visually.

**Defects found by testing and fixed:**
- Session cookie lacked `HttpOnly` and `SameSite` ([D-047](decisions.md)).
- HTTP/2 upgrade lost the signed request body. Mocks did not show this; the live status check did.
- Async psycopg failed on the Windows event loop.
- Public pages were prerendered, which would have broken them under the nonce CSP.
- Pooled AI connections went stale after a database restart ([D-048](decisions.md)).

**Not verified:**
- CI has never run, as there is no Git remote.
- Neon has not been connected. All testing used local PostgreSQL 18.3.
- Setup was run on this machine, not from a truly fresh clone.
- A Windows-only environment: Linux and macOS are untested locally.
- WCAG conformance has not been formally audited. Keyboard and label checks are limited to what the E2E tests exercise.
- Docker Desktop does not start here (see Open items).

### 2026-11-06: M0 Project foundation

**Implemented:** repository structure, `CLAUDE.md`, the documentation system, architecture, flows, security, testing, roadmap, decisions, and module plans.

**Verified:**
- Installed toolchain: Java 21.0.12, Python 3.14.3, Node 25.7.0, Docker 29.2.1, Git 2.56.
- Current versions checked against package registries.
- Planned Python dependencies resolved on 3.14 with wheels only (`pip --dry-run`).
- OpenRouter models and ZDR endpoints checked against its public API.
- Neon extension availability checked against its docs; `pg_search` found retired.

**Not verified:**
- Boot 4.1.2 with Temporal 1.40: the spike was blocked by local permission policy.
- No model has been run; all routes in [D-026](decisions.md) are provisional.
- Neon and Temporal have not been connected.
