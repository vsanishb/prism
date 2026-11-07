# Progress

The factual record of project state. Update after meaningful work. Newest entries go first in the log.

**Status terms:** `Planned` · `In progress` · `Implemented` (code exists) · `Tested` (verified at the required levels) · `Deferred`

## Current state

**Phase:** M4 complete. M5 is next. M1's CI workflow has still never run.
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
| Temporal workflows | Tested | M3 |
| Document intelligence | Tested, on synthetic documents only | M3 |
| Policy intelligence | Tested, on synthetic policies only | M4 |
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
| Confirm the OpenRouter account is set to "ZDR only". Prism enforces ZDR per request regardless | User | Before real use |
| Make pgvector available without Docker: a native install or Neon ([D-056](decisions.md)) | Dev and user | M5 |

## Log

### 2026-11-07: M4 Policy intelligence, and cheaper models

**Models:** at the user's request on cost, every route now uses DeepSeek V4.1 Flash ([D-070](decisions.md)). It passes the zero-retention check, so the check stays on. On it, claim extraction met every target at $0.025 for 17 documents. Before that choice, eight cheap models were compared ([D-069](decisions.md)).

**Implemented:** policy page extraction, `PolicyIngestionWorkflow`, assembly across overlapping batches, relations, validation against the register, value review with correction, publication with separation of duties, immutability enforced by the database too, model usage records for policies, and the policy review screens.

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 334 passed |
| AI (pytest, no network) | 176 passed. `ruff` and `mypy --strict` clean |
| Web unit (Vitest) | 12 passed. ESLint and `tsc` clean |
| End-to-end, production build, real Temporal and AI service, stand-in model provider | 43 passed, covering M1 to M4 |
| Policy evaluation, real model, 3 synthetic policies | All targets met: 49 of 49 critical values, 53 of 53 provisions. $0.06 |

Also verified: the Platinum policy read through the browser on the real model, with clause 4.5 whole across its page break and its exception separate. Screens reviewed at desktop and phone width on that output.

**Defects found by testing and fixed:**
- A row lock inside a read-only transaction, refused by PostgreSQL.
- An assembly failure was recorded as a page batch, which would have shown reviewers a false page range.
- Choosing a value on a phone did not bring its page into view.
- The rejection reason error was not shown beside its field.
- Policy model usage was not recorded. Found by review of the finished work.
- Durations written with words between number and unit, or as a bare number, were refused.

**Not verified:**
- Accuracy on real policies, which are longer and messier. All real-model runs fit one batch, so cross-batch joining is tested only with scripted results.
- Restart tests were not repeated for policy ingestion. It uses the same workflow, outbox, and heartbeat code as claims.
- The user's local database is not migrated: `db-reset` or `migrate` is needed before running M4.
- Everything listed under earlier milestones, including CI and Neon.

### 2026-11-07: M3 Document intelligence and workflows

**Implemented:** model gateway with retention enforcement, sandboxed rendering, classification, extraction, grounding, scan re-reading, the Temporal worker and `ClaimProcessingWorkflow`, the outbox relay, stage events, consolidation, validation, the wait for documents, stage retry, the processing panel, the fact review screen with source highlighting, the evaluation set, and a separate end-to-end stack.

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 290 passed |
| AI (pytest, no network) | 151 passed. `ruff` and `mypy --strict` clean |
| Web unit (Vitest) | 12 passed. ESLint and `tsc` clean |
| End-to-end, production build, real Temporal and AI service, stand-in model provider | 38 passed, covering M1 to M3 |
| Real processes killed mid-work (`scripts/chaos_test.py`) | 5 of 5 scenarios passed |
| Evaluation with real models, 17 synthetic documents | All targets met: 64/64 critical, 8/8 monetary, 0 invented of 17, 0 injected. $0.33 |

Also verified: one claim through the browser on real models, with a conflict and a scan, costing about $0.07 and served through ZDR routes. Screens reviewed at desktop and phone width on that claim's real output. A scan of 802 service log lines found no patient, diagnosis, or amount text, and no API key.

**Defects found by testing and fixed:**
- Every endpoint of the planned extraction model retains flagged content, so it was replaced ([D-057](decisions.md)).
- `Rs. 11,000/-` failed to parse, and `12,34` was read as 1,234. The second would have been a silent misread.
- Killing core stalled a claim for up to five minutes ([D-063](decisions.md)).
- The conflict label counted sources instead of distinct values, showing 3 for 2. Found by reviewing real output.
- `require_parameters` removed every ZDR endpoint, and the schema exceeded the provider's limit on nullable fields ([D-061](decisions.md)).

**Not verified:**
- Accuracy on real documents. The evaluation set is small and clean, its "handwriting" is a script font, and prompts and parsing were corrected against it. Perfect scores here do not predict real accuracy.
- That the OpenRouter account is set to "ZDR only". Prism enforces ZDR on each request regardless.
- Everything listed under M1 still applies, including CI and Neon. The CI workflow's new dependencies have not run.
- The `run-*` commands with the M3 code, as the user's M2 services were left running. All M3 runs used `e2e-stack`.
- `run-web` was not started separately. Fuzzing and independent review of parsing remain M9.
- Core still parses uploads in-process, with tested bounds but no way to kill a runaway parse. Added to M9.
- Formal accessibility audit. Keyboard and label checks are limited to what the E2E tests exercise.

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
