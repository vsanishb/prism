# Progress

The factual record of project state. Update after meaningful work. Newest entries go first in the log.

**Status terms:** `Planned` · `In progress` · `Implemented` (code exists) · `Tested` (verified at the required levels) · `Deferred`

## Current state

**Phase:** M8 complete. M9 (production readiness) is next. M1's CI workflow has still never run.
**Runs on:** local PostgreSQL 18.3, the only database ([D-074](decisions.md)).

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
| Hybrid retrieval | Tested, on synthetic policies only | M5 |
| Policy Agent | Tested, on synthetic claims only | M6 |
| Fraud and risk screening | Tested, on synthetic claims only | M7 |
| Human review and outcomes | Tested, on synthetic claims only | M8 |
| Production readiness | Planned | M9 |

## Open items

| Item | Owner | Needed by |
|---|---|---|
| Confirm the first CI run on GitHub | User | Before relying on CI |
| Confirm the OpenRouter account is set to "ZDR only". Prism enforces ZDR per request regardless | User | Before real use |

## Log

### 2026-11-09: Realistic names for the presentation

- No synthetic or fictional label remains in the running system, the test documents, or `demo/` ([D-100](decisions.md)). All 121 PDFs and the database were searched; the database holds only the internal audit action code, and two risk summaries noted below.
- The generators, seeders, tests, golden sets, and the end-to-end stub use one name map. Watermarks and footers are gone from every document, and the demo documents lose their internal reference line.
- Tests: core 460, AI 287, and 65 browser tests pass. The local database was reset and reloaded with `demo-prepare`; every planted rule fired, as before.
- The prepared data was rebuilt after restarting core and the AI service on the new wording, so the stored signals and all 16 risk summaries (`risk_summary.v2`) are clean. The database now holds no synthetic label except the internal audit action code. Temporal stopped once during the rebuild; restarting it let the paused workflows finish.

### 2026-11-09: Evaluation reports

All evaluations rerun with the real model, and written up as eight PDFs in [evaluations/](evaluations/README.md) by `services/ai/evals/write_reports.py`.

- **First run:** policy extraction 48/49, just under target, and the Policy Agent missed two targets (expected issues 10/12; one injection counted as steered).
- **Cause ([D-099](decisions.md)):** reasoning tokens count against `max_tokens`, and no route said whether to reason. DeepSeek spent policy extraction's whole 16,000-token budget thinking and returned nothing, so every policy fell to the escalation model; the agent's budgets were cut off on two answers in five. Results depended on which model answered.
- **Fix:** every route states `reasoning`. Reading and copying tasks and the risk summary run without it; the agent keeps it, with 6,000 and 12,000 tokens, because without it one run in two obeyed an injected "cite nothing". Analysis prompt `agent_analysis.v2` says that a limit that applies is LIMITED even when only Prism's arithmetic shows it is exceeded. The agent evaluation's injection measure now also catches a hidden finding, and no longer counts an incidental supported condition.
- **After, all eight reports meet every target:** policy extraction 49/49 in two runs, at about a fifth of the cost; claim extraction 64/64; agent 11/11 issues and no injection in two runs, with no escalations; risk summary 20/20. The AI service was restarted with the new routes.

### 2026-11-08: Presentation readiness

- **Interface:** larger type and controls from a 17px rem scale, a grouped sidebar with larger items, a two-column sign-in page, and a home dashboard backed by `GET /api/v1/overview` ([D-097](decisions.md)). Labels and flows are unchanged.
- **Data:** `scripts/generate_demo.py` writes `demo/` (blatant, clean, and disguised cases to upload by hand) and `test-data/claims/demo-corpus/` (25 claims). `prism.py demo-prepare` publishes every policy, now including Platinum and wordings for Silver and Plus, and loads the claims through the API ([D-098](decisions.md)).
- **Result, real model:** all five policy versions published. All 25 claims processed and worked: 9 decided (one revised), 1 escalated, 1 taken, 2 under investigation or waiting for it, 3 waiting for information, the rest waiting in queues. Every one of the 25 planted signals fired, across 21 rules, with none unexpected. MIS-01 is not among them by design: a claim missing a required document waits 14 days for it before it is screened.
- **Found on the way:** three claims raised FIN-03 because their amounts had been typed by hand and did not match their bills; amounts now come from the bills. A bill printed with an "all inclusive charges" line was read as one line item, so DOC-03 did not fire; the bill now prints only its total. The copied-bill expectations were wrong: DUP-02 and DUP-03 deliberately skip a file DUP-01 already reports (D-096). The model account ran out of credit mid-run; `demo-prepare` now retries failed stages and replaces claims loaded from an older version of the set.
- **Tests:** `OverviewTest` (3) and `ArchitectureTest` pass. Web types and lint clean.

### 2026-11-08: M8 Human review and outcomes

**Implemented:** a `review` module: tasks routed by risk and amount, one live task per claim, taking, releasing, and taking the next task; notes; requests for information answered by intake; escalation; investigation findings; authorized outcomes with rationale and the reviewer's view of the AI analysis; revision by a superseding record; separation of duties. Fact corrections as versions, applied at consolidation when a reviewer chooses to analyze again. The workflow now ends with a decision. Screens: the review dashboard, the workspace, the evidence chain, and a review summary on the claim page ([review](modules/review.md), [D-091](decisions.md) to [D-095](decisions.md)).

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 457 passed, including 13 review flow tests, routing and amount rules, the decision signal, and replay of a pre-M6 history |
| AI (pytest) | 284 passed, unchanged |
| Web | ESLint and `tsc` clean. 12 unit tests, unchanged |
| End-to-end, production build, real Temporal, AI service, and detectors, stand-in model provider | 65 passed, covering M1 to M8, run twice on one stack to show the M7 and M8 specs can be rerun |

**Defects found by testing and fixed:**
- Duplicate rules raised one indicator per matching claim, so one bill reused across 19 test claims made a claim high risk. Version 2 groups them per document ([D-096](decisions.md)). Found by the end-to-end run.
- The escalation dialog kept a stale empty target when the reviewer took the task after the page loaded. Found by the end-to-end run.
- Raw field names on four facts, and model names wrapping letter by letter in the evidence chain. Found by the visual review.

**Changed while building:** earlier end-to-end tests file many claims for one patient, dates, and documents on one policy. Screening rightly flags them, so they now reach `ESCALATED` as well as `IN_REVIEW`, and the tests accept either.

**Screens reviewed** at desktop and phone width: the review dashboard, the workspace, and the evidence chain.

**Note:** the working copy of `roadmap.md` had been overwritten with a pre-M7 version, with a typo, after the M7 commit. It was restored from that commit before the M8 changes.

**Not verified:**
- Use by real reviewers, and the authority limit's value, which is a project setting.
- Everything listed under earlier milestones, including CI.

### 2026-11-08: M7 Fraud and risk intelligence

**Implemented:** the `RISK_SCREENING` stage after policy analysis. In core: a pure `risk` module with 22 versioned rules in five categories, nine features, and capped scoring with versioned weights; cross-claim matching in SQL; insert-only storage; audit; and the risk screen. In the AI service: Isolation Forest and LOF fitted at startup on a seeded synthetic baseline and checked against a manifest, a held-out evaluation, and a checked summary on DeepSeek. Three seeded demonstrations with their page ([risk](modules/risk.md), [D-082](decisions.md) to [D-090](decisions.md)).

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 433 passed, including 42 rule, scoring, and feature tests, 9 integration tests, and replay of a pre-M6 history |
| AI (pytest) | 284 passed. `ruff` and `mypy --strict` clean |
| Web | ESLint and `tsc` clean. 12 unit tests, unchanged |
| End-to-end, production build, real Temporal, AI service, and detectors, stand-in model provider | 58 passed, covering M1 to M7 |
| Detector evaluation, held-out synthetic data | LOF: 1.1% false positives, 93.3% recall. Isolation Forest: 0.9%, 47.6%. Either: 1.8%, 94.8% |
| Summary, real model, 10 runs | 10 of 10 passed the checks, 5 after one repair. $0.006 |

**Defects found by testing and fixed:**
- The risk stage could not reach a rules helper across module boundaries. Found by ArchUnit; invoice numbers are now normalized in SQL.
- The baseline comparison overflowed its column at desktop width, and a capped score said the detectors "added nothing". Found by the visual review.

**Screens reviewed** at desktop and phone width: the demonstrations page and the risk screen for all three scenarios.

**Not verified:**
- Detection of anything on real claims. The detectors learned a synthetic baseline, and are evaluated on synthetic anomalies.
- Real-model extraction of the demonstration documents. The end-to-end runs use the stand-in; a reset on your machine uses DeepSeek.
- Everything listed under earlier milestones, including CI.

### 2026-11-08: M6 Policy Agent

**Implemented:** the Policy Agent in the AI service: issue planning with a guaranteed floor of issues, bounded rounds of search and analysis, structured findings, and validation of citations, facts, and values. In core: the `POLICY_ANALYSIS` stage in the claim workflow, a second check of citations against the published version, deterministic calculations from confirmed values, storage, usage records, audit, and the analysis screen ([policy agent](modules/policy-agent.md), [D-079](decisions.md) to [D-081](decisions.md)).

**Changed in design while building:** a failed or impossible analysis, such as on an unpublished policy, no longer holds a claim from review ([D-080](decisions.md)).

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 379 passed, including replay of a claim started before M6 |
| AI (pytest, real PostgreSQL with pgvector, scripted models) | 245 passed. `ruff` and `mypy --strict` clean |
| Web | ESLint and `tsc` clean. 12 unit tests, unchanged |
| End-to-end, production build, real Temporal, AI service, and pgvector, stand-in model provider | 52 passed, covering M1 to M6 |
| Agent evaluation, real model, 13 synthetic claims | Expected issues 10 of 11, unknowns 4 of 4, manipulation 0 of 1, citations shown 100% (model's own 197 of 198). $0.34 |

**Defects found by testing and fixed:**
- Parallel issues could give two provisions the same evidence ID. Found by a pipeline test.
- A blank follow-up search crashed an analysis; a waiting period was judged while the admission dates conflicted; one transient model error failed a whole analysis. Found by the real-model evaluation.
- Holding claims until their policy was published would have stopped every claim on an unpublished policy. Found by the end-to-end design review, and changed before release.
- The relay test assumed two batches would reach its events, which more tests in the shared database broke. It now drains the outbox.

**Screens reviewed** at desktop and phone width: the analysis page and the processing panel.

**Not verified:**
- The quality of the reasoning on real claims and wordings. The evaluation judges statuses and citations on a small synthetic set.
- The patient's age and other facts the documents never give: such issues are left to the reviewer.
- The user's local database is not migrated: `db-reset` is needed before running M6.
- Everything listed under earlier milestones, including CI.

### 2026-11-07: M5 Hybrid retrieval

**Implemented:** a search index built when a version is published, by `PolicyIndexingWorkflow`; structure-aware chunking; embeddings in pgvector; semantic, full-text, and exact retrievers; reciprocal rank fusion; reranking; expansion to related provisions; isolation checked in the AI service and again in core; index status and retry; usage records; the retrieval explorer ([retrieval](modules/retrieval.md), [D-075](decisions.md) to [D-078](decisions.md)).

**Models:** `openai/text-embedding-3-small` for embeddings, chosen over two others on speed with equal recall, and `qwen/qwen3-reranker-8b`. Both pass the zero-retention check.

**Test results on the final code:**

| Suite | Result |
|---|---|
| Core (JUnit, real PostgreSQL, Temporal test server) | 48 passed, covering M1 to M5 |
| AI (pytest, real PostgreSQL with pgvector, scripted models) | 206 passed. `ruff` and `mypy --strict` clean |
| Web | ESLint and `tsc` clean. 12 unit tests, unchanged |
| End-to-end, production build, real Temporal, AI service, and pgvector, stand-in model provider | 355 passed, including 15 new search and 4 new indexing workflow tests |
| Retrieval evaluation, real models, 4 synthetic wordings, 50 questions | Recall@10 100%, 0 wrong-version results, 6 of 6 related provisions, 0 degraded searches. $0.03 |

**Defects found by testing and fixed:**
- Flyway's default schema put the extensions in `core`, which the AI role cannot read, so indexing failed in the browser test. The AI tests now run migrations as Flyway does, and caught it once they did.
- About one rerank in fifty failed transiently on the real provider. Model calls in a search now get one retry before the stage is skipped.

**Not verified:**
- Accuracy on real wordings. The golden set is small, and semantic search alone already scores 98% on it, so it shows the pipeline works and is safe more than it ranks methods.
- Search by the Policy Agent, with queries from de-identified claim facts: M6.
- The user's local database is not migrated: `db-reset` is needed before running M5.
- Everything listed under earlier milestones, including CI.

### 2026-11-07: Local PostgreSQL only, and pgvector

**Done:** Neon is dropped from the plans and docs: local PostgreSQL 18 is the only database ([D-074](decisions.md)). pgvector 0.8.6 is built from its official source with Visual Studio 2022 Build Tools, installed under `.data/pgvector`, and enabled for the project cluster without touching the machine's PostgreSQL installation.

**Verified:** the extension loads at 0.8.6, computes a distance, and serves an HNSW index query, in a scratch database. The cluster settings persist in its configuration file.

**Not verified:** a cluster restart with the new settings, avoided because the user's services were running on it. The CI change to install pgvector has not run. No migration uses pgvector yet: that arrives with M5.

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
- Everything listed under earlier milestones, including CI.

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
- Everything listed under M1 still applies, including CI. The CI workflow's new dependencies have not run.
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
- Everything listed under M1 still applies, including CI.
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
