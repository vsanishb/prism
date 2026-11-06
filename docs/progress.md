# Progress

The factual record of project state. Update after meaningful work. Newest entries go first in the log.

**Status terms:** `Planned` · `In progress` · `Implemented` (code exists) · `Tested` (verified at the required levels) · `Deferred`

## Current state

**Phase:** M2 complete. M3 is next. M1's CI workflow has still never run.
**Runs on:** local PostgreSQL 18.3. Neon has not been connected.

| Capability | Status | Milestone |
|---|---|---|
| Documentation foundation | Implemented | M0 |
| CI pipeline | Implemented, never run | M1 |
| MFA, key rotation, self-service password change | Deferred | M9 |
| Authentication, RBAC, audit | Tested | M1 |
| Service-to-service authentication | Tested | M1 |
| Design system and app shell | Tested | M1 |
| Claims, policies, documents | Tested | M2 |
| Synthetic document set | Implemented | M2 |
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
| Provide OpenRouter key, set account to ZDR only | User | M3 |
| Make pgvector available without Docker: a native install or Neon ([D-056](decisions.md)) | Dev and user | M5 |

## Log

### 2026-11-06: M2 Claims and documents

**Implemented:** policy register, claim lifecycle, secure document pipeline, server-side page rendering, claim history, synthetic documents and seed data, and all M2 screens. Also PowerShell-safe `run-ai`, `run-core`, and `run-web` commands, after the M1 guide's commands failed in Windows PowerShell.

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL) | 179 passed |
| AI (pytest) | 22 passed. `ruff` and `mypy --strict` clean |
| Web unit (Vitest) | 7 passed. ESLint and `tsc` clean |
| End-to-end, production build (Playwright) | 30 passed, covering M1 and M2 |

Also verified: a valid 23 MB scan and a refused 27 MB file through the real proxy, and `run-ai` and `run-core` started from Windows PowerShell. Screens were reviewed at desktop width, and the claim page at phone width.

**Defects found by testing and fixed:**
- Next.js cut proxied uploads off at 10 MB, so valid files of 10 to 25 MB would have failed ([D-054](decisions.md)).
- A non-compiling test was committed by a chained command, and corrected before anything was shared.

**Not verified:**
- Everything listed under M1 still applies, including CI and Neon.
- PDF parsing runs in the core service on untrusted files. Limits are in place, but it has had no fuzzing or independent review.
- No malware scanning (M9), and local document storage is not encrypted.
- `run-web` was not started separately, because a Next.js dev server was already running. It runs `npm run dev`, which was tested in M1.

### 2026-11-06: After M2 review

**No Docker:** recorded as [D-056](decisions.md). CI now installs PostgreSQL natively; this change has not run.

**Temporal, tested natively:** server and web UI started from PowerShell with no Docker, and a test workflow survived a full restart. No Prism workflow exists yet.

**Verified:** 16 M2 end-to-end flows on the dev server, including the 23 MB upload and the 27 MB refusal, then all 31 M1 and M2 flows on the production build. Every M2 screen reviewed at phone width, with no sideways page scrolling.

**Defect found and fixed:** phone tables hid claim status and amount off-screen. Claims and policies now use stacked lists on phones, and a test covers this.

**Known gap:** the M1 Users and Audit tables still scroll sideways on phones, so their last columns start off-screen.

**Scheduled:** malformed-PDF tests in M3. Fuzzing, independent review, malware scanning, and encrypted storage in M9.

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
