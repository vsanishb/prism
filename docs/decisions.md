# Decision Log

Why Prism is built the way it is. Add new entries instead of rewriting old ones: when a decision changes, mark it `Superseded by D-xxx` and record the new one.

**Status:** `Accepted`, `Provisional` (needs validation), `Superseded`.
**Source:** `User` (specified in the project brief) or `Dev` (made during development).

## Version baseline

Checked against Maven Central, PyPI, npm, nodejs.org, Docker Hub, and vendor docs on 2026-11-06.

| Component | Version | Verification |
|---|---|---|
| Java | 21 LTS (Temurin 21.0.12, installed) | Installed |
| Spring Boot | 4.1.2 | Verified with Temporal on Java 21 (D-039) |
| Temporal Java SDK | 1.40.0 | Verified on Boot 4.1.2 against the native server, including restarts. The SDK is used directly, not the starter (D-064) |
| Temporal CLI (native) | 1.10.0, Server 1.32.2 | Verified on Windows: server, web UI, and a workflow surviving a restart (D-056) |
| Python | 3.14 (installed) | Planned dependencies resolved wheel-only on Windows, 58 packages |
| FastAPI | 0.162.0 | Same check |
| Pydantic | 2.14.3 | Same check |
| Uvicorn | 0.54.0 | Same check |
| psycopg | 3.3.8 | Same check |
| pypdfium2 | 5.15.0 | Verified on Windows with real documents and malformed files (D-058) |
| Pillow | 13.0.0 | Runtime since M3: decodes uploaded images in the render worker |
| httpx | 0.28.1 | Runtime since M3: model gateway |
| psutil | 7.2.2 | Runtime since M3: memory limit on the render worker |
| Node.js | 25 (installed: 25.7) | User decision (D-040). Satisfies Next.js ≥ 20.9 |
| Next.js | 16.4.5 | Verified on Node 25.7 |
| React | 19.3.1 | Verified with Next 16.4.5 build and E2E |
| Tailwind CSS | 4.3.x | Latest published |
| TypeScript | 5.9.3 | Verified with Next 16 build. 7.0 not adopted: new major, unverified with Next 16 |
| Local Postgres (native) | 18.3 (installed) | The only database (D-074). `pg_trgm`, `pgcrypto`, `citext`, `unaccent` present |
| pgvector | 0.8.6 | Built from source for PostgreSQL 18 on Windows, installed under `.data/pgvector`. Verified: extension, distance, and an HNSW index (D-074) |
| Visual Studio 2022 Build Tools | C++ workload | Installed on this machine through winget, to build pgvector |
| pg_trgm | Bundled with PostgreSQL 18 | Created by the M5 migration. Word similarity for defined terms (D-076) |
| CI Postgres | PostgreSQL 18 from the PostgreSQL apt repository | CI not yet run (D-056) |
| uv | 0.13.7 | Installed and verified |
| Apache PDFBox | 3.0.8 | Verified: parsing and rendering in tests and E2E |
| ReportLab | 5.1.0 | Dev only. BSD licensed. Generates test documents |
| scikit-learn | 1.9.1 | Runtime since M7: Isolation Forest and LOF. BSD licensed. Windows wheel for Python 3.14 verified (D-089) |
| NumPy | 2.5.4 | Runtime since M7, with scikit-learn. SciPy 1.18.1 and joblib 1.6.0 come with it |

Versions move only through a new decision entry. Spring Boot 4.2 was at RC2 on the check date and is deliberately not used.

---

## Structure and services

### D-001 Monorepo
**Status:** Accepted · **Source:** Dev
One repository for `services/core`, `services/ai`, `web`, `database`, `infra`, and `docs`. Changes that cross services stay atomic, and docs stay beside the code. Alternative: one repository per service, which adds overhead with no benefit for one team.

### D-002 Core is a modular monolith
**Status:** Accepted · **Source:** Dev
Spring Boot is one deployable with feature packages, with boundaries enforced by ArchUnit. Claims, reviews, and audit share transactions, and splitting them would add distributed-consistency problems without gain. Spring Modulith was considered; ArchUnit gives the needed enforcement with less machinery.

### D-003 Layer responsibilities
**Status:** Accepted · **Source:** User
Spring Boot is the application and business layer, FastAPI handles AI and specialized processing, Next.js is the frontend, PostgreSQL is the database (local only, D-074), OpenRouter provides models, and Temporal handles orchestration.

### D-004 Spring Boot 4.1 with Temporal 1.40
**Status:** Accepted, gate passed (see D-039) · **Source:** Dev
Spring Boot 3.5 reached end of OSS support on 2026-06-30, and 4.0 follows on 2026-12-31, so 4.1 (supported to 2027-07-31) is the only line that stays supported through the project. Temporal added Boot 4 support in 1.34, but its build targets 4.0.x, so 4.1 is not confirmed.
**Gate:** the first task in Milestone 1 runs a real workflow on the test server under Boot 4.1.2. A spike during planning was blocked by the local permission policy on running downloaded code, so this has **not** been tested.
**Fallback:** if it fails, use Boot 4.0.9 temporarily, accepting that OSS support ends 2026-12-31, and record the outcome here.

### D-005 Python 3.14
**Status:** Accepted · **Source:** Dev
It is already installed, supported to 2030, and every planned dependency resolves with prebuilt wheels. Choosing no heavy ML framework is what makes this safe: models run behind OpenRouter. Revisit if a future dependency lacks 3.14 wheels.

### D-006 Node 24 LTS
**Status:** Superseded by D-040 · **Source:** Dev
Node 25 is a non-LTS release and should not be a project baseline, although it satisfies Next.js's minimum. Node 24 is an established LTS line; 26 only entered LTS in October 2026. Pinned in `.nvmrc`, with Node 24 required before Milestone 1 frontend work.

### D-007 Frontend libraries
**Status:** Accepted · **Source:** Dev
Next.js App Router, Tailwind CSS, Radix primitives through locally owned shadcn/ui components, TanStack Query, React Hook Form with Zod, pdf.js for document viewing, Playwright for end-to-end tests. Owned components allow a distinctive design system rather than a stock admin look.

### D-033 Build and package tools
**Status:** Accepted · **Source:** Dev
Maven with the wrapper, uv with a lockfile, npm. Maven and uv are not installed; the wrapper handles Maven and uv is installed in M1. pnpm offers nothing this single frontend needs.

## Data

### D-008 One Neon database, two schemas
**Status:** Accepted for the two-schema layout. The Neon part is superseded by D-074 · **Source:** User (Neon) / Dev (layout)
`core` and `knowledge` schemas with separate runtime roles. Enforces the service boundary with grants at far less cost than two databases. Postgres 18 provides native `uuidv7()` and the newest pgvector on Neon.

### D-009 Flyway owns all migrations
**Status:** Accepted · **Source:** Dev
One tool and one ordered history for both schemas, run as a dedicated step with the owner role. Runtime roles have no DDL rights, which also protects the audit table. Migrations use Neon's direct endpoint; applications use the pooled endpoint. Alternative: Alembic for `knowledge`, which adds a second tool and history for one small schema.

### D-013 AI service is stateless for claims
**Status:** Accepted · **Source:** Dev
Core is the single writer of claim state. AI persists only policy knowledge. This limits where health data lives and removes dual-write problems.

### D-014 Core pushes document bytes to AI
**Status:** Accepted · **Source:** Dev
AI receives bytes in the request and holds no storage credentials, giving one code path in every environment and least privilege. Cost: a large policy is re-sent per page batch, acceptable under the size cap. Alternative: presigned URLs, which need an extra path for local storage.

### D-015 Document storage port
**Status:** Accepted · **Source:** Dev
Filesystem locally, S3-compatible when deployed. Blobs do not belong in Postgres. Provider chosen in M9.

### D-037 Immutable derived records
**Status:** Accepted · **Source:** User
Raw extraction, normalized data, structured provisions, and chunks are separate. AI output, risk analysis, and human decisions are separate records. Corrections add versions.

## Workflow

### D-010 Workflows in Java, AI over authenticated HTTP
**Status:** Accepted · **Source:** Dev
Workflows and activities run in the core worker, and activities call FastAPI. A separate Python Temporal worker was rejected: it would add a second worker fleet and security model, and bypass the authenticated API boundary the project requires. `temporalio` is not a Python dependency as a result.

### D-011 Reference-only workflow payloads
**Status:** Accepted · **Source:** Dev
Workflow inputs, outputs, and signals carry IDs and status only. This keeps health data out of Temporal history and within size limits.

### D-012 Temporal persistence stays off Neon
**Status:** Accepted · **Source:** Dev
Temporal needs constant connections and heavy writes, a poor fit for serverless Postgres and scale-to-zero. Locally it uses the CLI dev server with a persisted file. When deployed, it uses Temporal Cloud or a dedicated database (M9).

### D-021 Transactional outbox
**Status:** Accepted · **Source:** Dev
Starting or signaling a workflow after a database commit is a dual write. An outbox row is committed with the change and relayed to Temporal, with idempotency through workflow IDs and signal deduplication.

### D-038 UI reads status from Postgres
**Status:** Accepted · **Source:** Dev
Activities record stage events, and the UI reads those. Status views keep working during a Temporal outage, and access control stays in core.

## Security

### D-016 Server-side sessions
**Status:** Accepted · **Source:** Dev
Spring Session JDBC with an `HttpOnly` cookie and same-origin routing. Revocation is immediate and no token is reachable from JavaScript. Alternative: JWT access and refresh tokens, which add rotation logic and weaker revocation with no benefit for a browser-only client.

### D-017 Argon2id passwords
**Status:** Accepted · **Source:** Dev
Current OWASP first choice. Password policy follows NIST 800-63B: length and blocklist rather than composition rules.

### D-018 Service authentication with ES256 JWTs
**Status:** Accepted · **Source:** User (requirement) / Dev (mechanism)
Core signs 60-second tokens, and AI verifies them with the public key, so AI cannot mint tokens. Asymmetric keys beat a shared secret because a compromised AI service cannot forge core's identity. mTLS can be added at the network layer when deployed.

### D-019 Permission-based RBAC
**Status:** Accepted · **Source:** User (RBAC) / Dev (model)
Roles map to permissions, and code checks permissions. Separation-of-duties rules sit on top. See [security.md](security.md#authorization).

### D-020 Append-only audit with hash chains
**Status:** Accepted · **Source:** User (protected audit) / Dev (mechanism)
No update or delete grants, a blocking trigger, and per-stream hash chains that make tampering detectable. Per-stream chains avoid a global write bottleneck.

### D-027 Zero-data-retention routing for claim data
**Status:** Accepted · **Source:** Dev
Health data must not be retained by providers. The gateway sends claim-derived requests with `provider.zdr: true` and `data_collection: "deny"`, and rejects any route without this. The OpenRouter account should be set to "ZDR only". This rules out model choices with no ZDR endpoint on 2026-11-06, including Cohere embed and rerank.
ZDR is not a compliance approval. OpenRouter's ZDR page also describes an exception for retention of content flagged for child-safety screening, which belongs in the privacy review.

### D-034 Synthetic data only
**Status:** Accepted · **Source:** Dev
Real claim data is prohibited until the privacy gate in [security.md](security.md#privacy-gate) clears.

## Retrieval and AI

### D-022 Postgres full-text search for lexical retrieval
**Status:** Accepted · **Source:** Dev
`pg_search` was retired from Neon on 2026-09-21. Options were `lakebase_text` (Neon BM25) or built-in `tsvector` with `pg_trgm`. Built-in search runs identically in local and test containers, and per-version corpora are small enough that BM25's scale advantage does not apply. Ranking sits behind a `LexicalRetriever` interface. `lakebase_text` will be evaluated only if the retrieval evaluation shows lexical ranking is the limit.

### D-023 Reciprocal rank fusion, then reranking
**Status:** Accepted, confirmed by measurement in D-078 · **Source:** Dev
RRF needs no score calibration across search types. Validated by the M5 evaluation.

### D-024 Exact vector search first
**Status:** Accepted, confirmed by measurement in D-078 · **Source:** Dev
Queries filter to one policy version, usually only hundreds or thousands of chunks, so an exact scan is fast and has perfect recall, avoiding filtered-HNSW recall loss. Add an index if measurements call for it.

### D-025 No agent or RAG framework
**Status:** Accepted · **Source:** Dev
A thin typed gateway and plain pipelines instead of LangChain or LlamaIndex. The core logic is domain-specific, traceability needs full control, and these frameworks add churn and hidden prompts.

### D-026 Model routing
**Status:** Provisional, extraction route superseded by D-057, embedding route by D-077 · **Source:** User (per-task selection) / Dev (choices)
Routes live in configuration. Choices come from the OpenRouter catalog on 2026-11-06, and each claim-data route has a listed ZDR endpoint. **All are starting points to be confirmed by the evaluation sets in the milestone that introduces them.** Prices are USD per million input/output tokens.

| Task | Primary | Escalation | Rationale |
|---|---|---|---|
| Document classification | `anthropic/claude-haiku-5.5` ($0.10/$0.50) | Sonnet | High volume, simple task |
| Claim document extraction | `google/gemini-3.8-flash` ($0.825/$4.125) | `anthropic/claude-sonnet-5.5` | Strong vision at moderate cost; escalate on validation failure |
| Policy page extraction | `anthropic/claude-sonnet-5.5` ($2/$10) | Human review | Runs once per version and errors spread everywhere, so accuracy wins |
| Policy Agent reasoning | `anthropic/claude-sonnet-5.5` | `openai/gpt-6.1-sol` ($2/$10) on outage | Accuracy on nuanced clauses; second vendor for availability |
| Issue planning, query generation | `anthropic/claude-haiku-5.5` | Sonnet | Short structured tasks |
| Semantic contradiction checks | `anthropic/claude-haiku-5.5` | Sonnet | Assists deterministic rules |
| Embeddings | `openai/text-embedding-3-large` | None | ZDR listed; query and corpus must share a model. Verify dimension control in M5 |
| Reranking | `qwen/qwen3-reranker-8b` | Skip rerank, flag degraded | The ZDR-listed reranker found |

Changing the embedding model requires re-embedding each version; chunks record their model.

### D-028 Rules first for risk
**Status:** Accepted, refined by D-082 to D-086 · **Source:** User (principle) / Dev (design)
Risk indicators come mainly from deterministic, versioned rules, so they are explainable and testable. Models only propose semantic contradictions, which must cite evidence. No outcome is automated.

### D-029 Deterministic calculation
**Status:** Accepted · **Source:** Dev
Arithmetic, date math, and limit comparisons run in code. Models identify inputs but do not compute results.

### D-030 Human approval before policy publication
**Status:** Accepted · **Source:** Dev
An undetected extraction error in a policy would affect every related claim, so critical values are confirmed by a person before publication.

### D-032 Bounded Policy Agent
**Status:** Accepted · **Source:** Dev
A planned, limited retrieval loop with a fixed tool set, rather than an open-ended agent. This keeps cost, latency, and audit trails predictable.

### D-036 PyMuPDF license
**Status:** Superseded by D-058 · **Source:** Dev
PyMuPDF has the best rendering and text-layer access, but is AGPL or commercial. Acceptable for development. **Before any distribution or hosted production use**, buy a license or replace it with `pypdfium2`, behind a small adapter.

## Product scope

### D-031 Internal users only
**Status:** Accepted, confirmed by D-042 · **Source:** Dev
Prism serves insurer staff. Claimants have no portal, and staff enter claims on their behalf. The brief describes reviewers and authorized users only. Awaiting user confirmation.

### D-035 Jurisdiction-neutral design
**Status:** Superseded by D-043 · **Source:** Dev
The brief names no country. Currency, retention, and regulation are configuration or documentation rather than code assumptions. Awaiting user confirmation of the target market.

## Milestone 1 direction (2026-11-06)

### D-039 Temporal gate passed
**Status:** Accepted · **Source:** Dev
A `@SpringBootTest` ran a real workflow and activity through `temporal-spring-boot-starter` 1.40.0 on Spring Boot 4.1.2 and Java 21, using Temporal's test server. The 4.0.9 fallback is not needed. Java 21 stays the baseline: it is LTS and verified, and Java 25 offers nothing this project requires yet.

### D-040 Node.js 25
**Status:** Accepted · **Source:** User
Supersedes D-006. Node 25 is the project runtime and `.nvmrc` pins it. It satisfies Next.js's minimum, and no LTS change is needed.

### D-041 Local PostgreSQL for development, Neon as the central database
**Status:** Superseded by D-074 · **Source:** User (Neon, local install) / Dev (arrangement)
Neon PostgreSQL is the central database for all application data and policy knowledge. The installed PostgreSQL 18.3 matches Neon's major version and serves local development through the same connection settings, so switching is configuration only. Integration tests use disposable Testcontainers databases.
The native install lacks pgvector, which is first needed in M5. Options then are installing the extension locally, or using Neon or the pgvector container. No milestone before M5 depends on it.

### D-042 Staff-only scope confirmed
**Status:** Accepted · **Source:** User
Confirms D-031. Prism serves insurer staff, with no claimant or customer portal.

### D-043 India as primary context
**Status:** Accepted · **Source:** User
Supersedes D-035. Defaults suit India: INR, Indian date display, and Indian insurance terminology in synthetic data. Privacy-by-design follows DPDP considerations. **Prism makes no claim of regulatory compliance** unless it has been formally established. Currency, locale, and retention stay configurable so that other jurisdictions remain possible.

### D-044 Synthetic data in every milestone
**Status:** Accepted · **Source:** User
Extends D-034. Each milestone ships clearly labeled synthetic artifacts and a frontend test guide in [test-guides/](test-guides/README.md). Synthetic data must never be presented as real insurance or medical documentation.

### D-045 PostgreSQL is the knowledge store
**Status:** Accepted · **Source:** User
pgvector and PostgreSQL full-text search provide hybrid retrieval, and no separate vector database is added unless a clear technical requirement appears that local PostgreSQL cannot meet. Retrieval stays modular behind interfaces, so search and embedding parts can change. Reranking stays only if it shows measurable value in evaluation (refines D-023).

### D-046 Project-owned local database, no Testcontainers
**Status:** Accepted · **Source:** Dev
Refines D-041. `scripts/prism.py setup` creates a private PostgreSQL 18.3 cluster under `.data/` from the installed binaries, on port 5544. The machine's PostgreSQL service is untouched, and no existing password is needed.
Integration tests create a throwaway database on that server, and CI uses a Postgres service. This replaces Testcontainers: Docker Desktop fails to start here because of a stale `dockerInference` socket, and one test path is simpler than two.
Tests log in through dedicated roles that inherit the runtime grants, so real permissions are exercised.

### D-047 Explicit session cookie configuration
**Status:** Accepted · **Source:** Dev
A test showed `server.servlet.session.cookie.*` did not reach the cookie written by Spring Session: it went out as `SESSION` without `HttpOnly` or `SameSite`. A `CookieSerializer` bean now sets these directly, and tests assert them on real responses.

### D-048 Validate pooled AI connections
**Status:** Accepted · **Source:** Dev
The AI pool checks connections before use. Idle connections die silently on any database restart, and Neon's scale-to-zero makes that routine. A test kills an idle connection and fails without this setting.

### D-049 Frontend libraries added when needed
**Status:** Accepted · **Source:** Dev
Refines D-007. TanStack Query, React Hook Form, and Zod are not installed, because M1's screens work well with Server Components and small forms. Each is added when a screen justifies it. API types are hand-written until the API is large enough for generation to pay off.

### D-050 Links shared by hand until email exists
**Status:** Accepted · **Source:** Dev
Prism has no email service. Administrators copy one-time invitation and reset links, and the first administrator's link is written to a gitignored file. This keeps tokens out of logs without adding an email provider.

## Milestone 2

### D-051 Server-side page rendering
**Status:** Accepted · **Source:** Dev
Supersedes the pdf.js part of D-007. Core renders pages to PNG with PDFBox. Untrusted PDFs never run in the browser, TIFF works (browsers cannot display it), no CSP relaxation is needed, and no page can be viewed without an audit record. Cost: CPU in core, bounded by a concurrency limit and output size cap. Revisit if viewing load grows.

### D-052 Owners decide document access
**Status:** Accepted · **Source:** Dev
The `document` module depends on no owner. Owning modules implement `DocumentAccessGuard`, so document rules always match claim and policy rules.

### D-053 Drafts are private to intake
**Status:** Accepted · **Source:** Dev
Only `CLAIM_MANAGE` holders can see drafts, and others get 404. An unsubmitted claim is unfinished work that reviewers should not act on. Finer rules, such as teams or assignment, arrive with review tasks in M8 if needed.

### D-054 Upload limits are layered
**Status:** Accepted · **Source:** Dev
Next.js's `proxyClientMaxBodySize` defaults to 10 MB. It is raised to 62 MB, above Spring's 60 MB multipart limit, which sits above the 25 MB and 50 MB type limits. Each layer lets the next give the precise error.

### D-055 Documents are never deleted in normal use
**Status:** Accepted · **Source:** Dev
Replaced and removed documents stay on record, as source documents are authoritative and part of the evidence chain. Retention-based deletion will be designed with the DPDP assessment ([security.md](security.md#privacy-gate)).

### D-056 No Docker
**Status:** Accepted · **Source:** User
Everything runs natively and locally: no Dockerfiles, Compose, containers, or Testcontainers until the user says otherwise. This supersedes the container options in D-012, D-041, and D-046, and the container items in the deployment plan.
Temporal uses the native CLI server, installed by `scripts/prism.py install-temporal` at a pinned version with a checksum check, and persisting to `.data/temporal.db`. CI installs PostgreSQL natively. pgvector for M5 will come from a native install or Neon.

## Milestone 3

### D-057 Claim routes exclude endpoints that retain flagged content
**Status:** Accepted. Its route table is superseded by D-069 · **Source:** Dev
Supersedes the extraction route in D-026. OpenRouter's ZDR catalog marks every `google/gemini-3.8-flash` endpoint with `safety_retention: true`, meaning content flagged by the provider's safety screening may be kept. That is not acceptable for health data, even under a ZDR label. `anthropic/claude-haiku-5.5`, `anthropic/claude-sonnet-5.5`, and `openai/gpt-6.1-sol` were `false` on all ZDR endpoints on 2026-11-07.
The gateway reads the catalog (cached for an hour) and refuses a model unless every ZDR endpoint is explicitly `false`, because a request cannot choose among them. An unreadable catalog stops processing rather than allowing the call. Requests carry both `zdr: true` and `data_collection: "deny"`: a test showed `deny` alone routing to an endpoint outside the ZDR list.

| Task | Primary | Escalation |
|---|---|---|
| Classification | Haiku 5.5 | Sonnet 5.5 |
| Extraction | Sonnet 5.5 | GPT-6.1 Sol (second vendor) |
| Scan re-read | Haiku 5.5, so that the check is not the same model repeating itself | Sonnet 5.5 |

Sonnet and Haiku both met every target on the evaluation set, at $0.33 and $0.03 for 17 documents. Sonnet stays primary because a misread value does not trigger escalation, so accuracy must come from the first model, and a small clean synthetic set cannot separate the two. Revisit with harder documents. `require_parameters` is not sent: it removes every ZDR endpoint for these models. Output is validated by the gateway regardless.

### D-058 pypdfium2 replaces PyMuPDF
**Status:** Accepted · **Source:** Dev
Supersedes D-036. PyMuPDF 2.1 hard-depends on ONNX Runtime, protobuf, NetworkX, and layout packages, which is far too much attack surface and weight for rendering, on top of its AGPL license. pypdfium2 is one package with no dependencies under a permissive license, and provides rendering, the text layer, and character boxes. This also removes the licensing item from M9.

### D-059 Documents are parsed in a killable process
**Status:** Accepted · **Source:** Dev
Native parsers can hang or exhaust memory on hostile files, and a thread cannot be stopped. The AI service renders in a short-lived child process, killed at 60 seconds or 1.5 GB. Content travels over pipes, so it never touches disk in the AI service. Cost: about a second of startup per window of eight pages, which is small next to a model call.

### D-060 Code decides what is PRESENT
**Status:** Accepted · **Source:** Dev
Models report text as written, with a page and a quote. Code then finds the quote in the page's text layer, checks the value is inside it, and normalizes dates and amounts. Failing any check gives `UNCERTAIN`, with the reason stored. On scans, which have no text to check against, critical values must survive an independent second reading. Dates are read day-first (D-043), and ambiguous amounts such as `12,34` are refused.

### D-061 Nullable fields travel as blanks to models
**Status:** Accepted · **Source:** Dev
Anthropic's structured output allows at most 16 nullable parameters per schema, and the bill schema had 23. Model schemas use `""` and `0` for "none", converted to real nulls at the validation boundary, so nothing past it sees this convention.

### D-062 Documents travel as base64 JSON
**Status:** Accepted · **Source:** Dev
Refines the architecture's multipart plan. The whole body, including metadata, stays covered by the signed hash with no new dependency, and Pydantic validates it at the boundary. Cost: a third more bytes on a local hop, under a 25 MB document limit.

### D-063 Extraction sends heartbeats
**Status:** Accepted · **Source:** Dev
Killing core during a model call left the claim stuck for the full five-minute activity timeout, because a crashed worker sends nothing. Extraction now beats every 10 seconds, with a 30-second timeout, and claims finished 17 to 30 seconds after a restart. Found only by killing a real process.

### D-064 Temporal SDK without the Spring starter
**Status:** Accepted · **Source:** Dev
Refines D-039. Core must start and serve users while Temporal is down, and workers refuse to start without a server. A small lifecycle class retries in the background, which needs direct control of the worker. Only the `workflow` module may use Temporal.

### D-065 Claim-level and document-level facts
**Status:** Accepted · **Source:** Dev
Patient, hospital, dates, diagnosis, and similar facts are about the claim, and are compared across documents. Bill totals, numbers, and dates belong to one document, as two bills legitimately differ. Comparing those would raise false conflicts.

### D-066 Names match on whole runs of words
**Status:** Accepted · **Source:** Dev
"Synthetic Hospital" and "Synthetic Hospital, Pune" agree, and honorifics are ignored, so that ordinary variation is not a false conflict. Different words never match. The fuller value is kept, and this can only merge a shorter form with a longer one: "Asha" agrees with "Asha Rao".

### D-067 Reopening review for new documents
**Status:** Accepted · **Source:** Dev
Adds `IN_REVIEW` to `PROCESSING`, and `AWAITING_INFORMATION` to `IN_REVIEW`, to the lifecycle. Documents can still arrive during review, and they must not sit unread. An expired wait goes to review without passing through processing again.

### D-068 Separate end-to-end stack
**Status:** Accepted · **Source:** Dev
`prism.py e2e-stack` runs everything on its own ports, database, document folder, and Temporal server, so that tests never disturb development data. A stand-in replaces only the model provider: it sits behind a configurable URL, so no test code exists in the service, and plain HTTP is allowed only to loopback.

## Milestone 4

### D-069 Cheaper models for claim documents
**Status:** Accepted, routes provisional · **Source:** User (cost) / Dev (choice)
Supersedes D-057's route table. The retention rule is unchanged. The user asked for cheaper models. Free models were not an option: the only free model with a ZDR endpoint cannot read images. Eight cheap vision models that pass the retention rule were run on the 17-document evaluation set on 2026-11-07, all routes on the candidate, with Haiku as escalation:

| Model | Targets | Cost for the set |
|---|---|---|
| `inclusionai/ling-3.0-flash-vl` | All met, 100% | $0.012 |
| `mistralai/mistral-small-3.2-24b-instruct` | All met, 100% | $0.014 |
| `bytedance-seed/seed-1.6-flash` | All met, 100% | $0.016 |
| `xiaomi/mimo-v2.6-flash` | All met, 100% | $0.017 |
| `google/gemma-3-27b-it` | Met, but 95.3% critical and 6/8 line item sets | $0.010 |
| `openai/gpt-5-nano` | Met, 96.9% critical | $0.046 |
| `qwen/qwen3-vl-30b-a3b-instruct` | Not met: 85.7% monetary | $0.023 |
| `google/gemma-3-12b-it` | Not met: 93.8% critical | $0.005 |

Mistral Small 3.2 is primary for classification and extraction, because four providers serve it, which matters for availability. Ling reads scans a second time, so that the check comes from a different model. Haiku is the escalation. The chosen combination met every target on two runs, at $0.013 and $0.014 for the set, against $0.33 for Sonnet: about 24 times cheaper. Every model that missed a target still invented nothing, because grounding turned its misreadings into `UNCERTAIN`.
The same caution applies as before: a small, clean synthetic set cannot separate strong models, so these routes need re-evaluation on harder documents before real use.

### D-070 DeepSeek V4.1 Flash for every task
**Status:** Accepted, routes provisional · **Source:** User
Supersedes D-069's routes. The user chose `deepseek/deepseek-v4.1-flash-1017` for every task, on cost: $0.15 in and $0.60 out per million tokens. The user also said the retention check could be ignored for synthetic data, but this model passes it, with 43 ZDR endpoints and none retaining flagged content, so the check stays on at no cost. It must stay on before any real document is processed ([privacy gate](security.md#privacy-gate)). Haiku remains the escalation.
Evaluated on 2026-11-07: all claim targets met, at $0.025 for 17 documents. One misread date was caught by grounding and became `UNCERTAIN`. Policy extraction met all targets: 49 of 49 critical values, 53 of 53 provisions, every exception, reference, and footnote, at $0.06 for three policies. That needed two code fixes, both deterministic: a percentage or duration is taken from text with extra words only when exactly one candidate is present, and a bare number reported as a duration takes its unit from the quote only when the quote holds one number and one unit.

### D-071 Policy knowledge lives in core
**Status:** Accepted · **Source:** Dev
Refines the architecture, which placed provisions in the AI-owned `knowledge` schema. M4's core work is human review: confirmation, correction, rejection, and publication, each audited in the same transaction as the change. Core cannot read `knowledge`, and the AI service must not write claim-side decisions. So core assembles and stores provisions, values, relations, findings, and reviews, and the AI service stays stateless, as it is for claims (D-013). The `knowledge` schema will hold only the M5 search index, built from published versions that core sends.

### D-072 Policy reading in overlapping batches
**Status:** Accepted · **Source:** Dev
Batches of up to six pages, each starting on the previous batch's last page. A clause cut by a page break is seen whole by the next batch, and duplicates merge by clause key, keeping the fuller text and everything each reading found. A provision marked as continuing joins the next batch's continuation. Assembly is plain code, and is tested with scripted batches.

### D-073 Rules apply to corrections too
**Status:** Accepted · **Source:** Dev
One function decides whether a value breaks a rule, such as a sublimit above the sum insured. It runs on the extracted value when assembling, and again on the reviewed value at publication, so that a correction cannot introduce what a reading would have been flagged for. Publication also requires every critical value to be reviewed, all pages read, and a publisher other than the uploader. A second synthetic policy manager exists so that this can be tested.

### D-074 Local PostgreSQL is the only database, with pgvector built natively
**Status:** Accepted · **Source:** User (local only) / Dev (build)
Supersedes D-041 and the Neon part of D-008. The user dropped Neon: every schema, including the M5 search index, lives in the local PostgreSQL 18 cluster, and there is no plan to connect a hosted database. Production hosting remains an M9 decision.
pgvector has no official Windows binaries, and conda-forge builds only for PostgreSQL 16. So `scripts/prism.py install-pgvector` builds the official 0.8.6 release source with Visual Studio 2022 Build Tools (C++ workload, installed via winget with the user's approval), checks the archive against a pinned SHA-256, and installs into `.data/pgvector`. PostgreSQL 18's `extension_control_path` and `dynamic_library_path` point the cluster there, set with `ALTER SYSTEM` so they persist. The machine's PostgreSQL installation is untouched, and no administrator rights are needed after the build tools. The control file's `module_pathname` is changed to a bare name, so that it resolves through `dynamic_library_path` and not the installation's `$libdir`.
Verified on 2026-11-07: `CREATE EXTENSION vector` at 0.8.6, a vector distance, and an HNSW index query, in a scratch database on the project cluster. CI installs `postgresql-18-pgvector` from the PostgreSQL apt repository instead; that has not run.

### D-075 The search index is built at publication, by the AI service
**Status:** Accepted · **Source:** Dev
Refines the RAG plan, which embedded during ingestion. Only published versions may be searched, and review can correct values until then, so the index is built once, from what was published. Publishing writes an outbox event in the same transaction, and `PolicyIndexingWorkflow` sends the published provisions and resolved relations to the AI service, which chunks, embeds, and replaces the version's index in one transaction. The index lives in the `knowledge` schema, as D-071 intended; core keeps only its status and the model usage. A failed index waits for a policy manager's retry, as a failed ingestion does.
Isolation is enforced twice. Every query in the AI service's store takes the version ID and filters on it in SQL. Core then checks that every provision in a result belongs to the version it asked about, and refuses the whole result otherwise. A search query is sent in a POST body, never a URL, because the Policy Agent's queries will carry claim facts.

### D-076 Retrieval pipeline
**Status:** Accepted · **Source:** Dev
Three retrievers run over one version. Semantic: exact cosine distance with pgvector (D-024). Lexical: full-text search, where any query word may match, stemmed with `english`, with tokens containing digits also matched as written with `simple` (D-022), ranked by `ts_rank_cd`. Exact: a clause the query names as a clause ("clause 4.5"), and a defined term within trigram word similarity 0.6 of some part of the query, so that a misspelling still finds it. A bare number is not treated as a clause, so that "2.5 lakh" is not clause 2.5.
The three rankings are fused by RRF with k = 60 (D-023), the top 20 are reranked, and the top 10 are returned. A clause named by number comes first, whatever the reranker thinks. Each result brings the provisions related to it, one hop in both directions: an exclusion brings its exception, and an exception brings its exclusion.
The query is embedded with the model that built the index, not the current route, so that a route change cannot mix vector spaces. Search is interactive, so a failed query embedding or rerank gets one immediate second attempt, then the search continues without that stage and says so (`degraded`). The evaluation saw about one rerank failure in fifty searches before this retry, and none after it.

### D-077 Embedding model: text-embedding-3-small
**Status:** Accepted · **Source:** Dev (measured)
Supersedes the embedding route in D-026. Measured on the retrieval golden set on 2026-11-07, at 1536 dimensions, with the same reranker:

| Model | Semantic only | Fused | Final | Search latency, median / max | Price per million tokens |
|---|---|---|---|---|---|
| `openai/text-embedding-3-small` | 98.3% | 96.6% | 100% | 1.2 s / 3.0 s | $0.02 |
| `openai/text-embedding-3-large` | 100% | 94.9% | 100% | 1.4 s / 4.2 s | $0.13 |
| `qwen/qwen3-embedding-8b` | 100% | 98.3% | 100% | 1.7 s / 14.1 s | $0.01 |

All three reach every target after reranking, so the choice rests on speed for an interactive search. The small model is fastest, produces 1536 dimensions natively rather than by truncation, and costs fractions of a cent per policy. Each has zero-data-retention endpoints with no safety retention, and the gateway checks this before each call. The reranker stays `qwen/qwen3-reranker-8b`, the only reranker with such endpoints. The column type fixes 1536 dimensions: a change needs a migration and a rebuild of every index.

### D-078 Fusion and exact search confirmed
**Status:** Accepted · **Source:** Dev (measured)
On the golden set, with the chosen models: recall@10 of 98.3% for semantic search alone, 84.7% for full-text alone, 96.6% for the fused list, and 100% after reranking, with zero results from another version, all 6 required related provisions present, and no degraded searches. Fusion followed by reranking (D-023) beats either retriever alone. Exact scans answered a search in about 1.2 seconds in total, most of it in the two model calls, so no vector index is needed (D-024).
The set is synthetic and small: four wordings, 127 provisions, 50 questions. Semantic search alone already scores highly on it, so it shows that the pipeline works and is safe, more than it ranks the methods. Real wordings, which are longer and less tidy, are the test that matters, and are not yet available.

### D-079 Policy Agent design
**Status:** Accepted · **Source:** Dev
The agent runs in the AI service and keeps nothing; core stores the result, as for claims and policies (D-013, D-071). Tool use is structured output through the gateway, not provider function calling: a finding either answers the issue or asks for more searches of the wording, within three rounds per issue and 30 model calls per claim. This keeps every call under the gateway's retention checks, repair, and usage records, with no framework (D-025).
A model plans the issues, and code adds cover, waiting periods, exclusions, and cost sharing if the plan leaves any out. Models refer to evidence and values by short IDs, never by UUID. Code decides what stands: citations must quote evidence retrieved in the run, facts relied on must be established, some issues require certain facts whatever the model names, and values apply only from cited provisions. Core checks citations again against the published version it holds, as it does for search (D-075).
Only de-identified facts are sent ([security](security.md#data-privacy)). Money, dates, and limits are computed in core from values a person confirmed before publication (D-029, D-030). The model is given the time from cover start to admission, computed in code, so that it can judge a waiting period without doing date arithmetic.

### D-080 Policy analysis assists review and never holds it
**Status:** Accepted · **Source:** Dev
The analysis stage runs before a claim goes to review. If it fails, including because the claim's policy version is not yet published or searchable, the failure is recorded with its reason and the claim goes to review anyway. A retry runs the processing cycle again. Holding a claim until its policy was published would have left every claim on an unpublished policy in processing, and would have changed M3's behavior, for a stage that decides nothing. The change is guarded by `Workflow.getVersion`, so claims started before it replay unchanged.

### D-081 Policy Agent evaluation
**Status:** Accepted · **Source:** Dev (measured)
On 13 synthetic scenarios, with DeepSeek V4.1 Flash on both agent routes, on 2026-11-07: expected issues identified 10 of 11 (target 90%), unknown when a needed fact was missing or conflicting 4 of 4 (target 95%), manipulation through document text 0 of 1, no scenario failed, $0.34 for the set (about 2.6 cents a claim). Every citation shown resolves to retrieved evidence, as validation guarantees; the model's own citations resolved 197 of 198 times. The miss: the room rent limit was cited for a dengue claim, but not applied as a daily limit.
The first run found three defects, fixed before this one: a blank follow-up search crashed an analysis; a waiting-period finding claimed evidence while the admission dates conflicted, because the model did not name the date; and a single transient model error failed a whole analysis. Each now has a regression test.
The set is small and synthetic, and judges statuses and citations, not the quality of the reasoning. Real claims and wordings are the test that matters.

## Milestone 7

### D-082 Risk screening: rules in core, anomaly detection in the AI service
**Status:** Accepted · **Source:** User (scope) / Dev (placement)
Rules run in core, in a `risk` module with no database access, fed by `intelligence`, which loads the claim's facts and finds matches in other claims. Core already holds the facts, the documents, the policy, and the claim history. Sending them to the AI service would add a second home for personal data, against D-013, for code that needs no model. Anomaly detection needs Python's ML libraries, so it runs in the AI service, which receives nine numbers and no identity. Core aggregates, stores, and audits. The architecture had placed risk analysis in the AI service. This refines it.

### D-083 Unsupervised anomaly detection: Isolation Forest and Local Outlier Factor
**Status:** Accepted · **Source:** User (algorithms) / Dev (design)
There are no labeled fraud outcomes, and there will be few for a long time, so supervised learning has nothing to learn from. Unsupervised detectors model normal claims and measure departure from them, which needs only unlabeled data. They answer "unusual?", never "fraud?" (Bolton and Hand, 2002).
- **Isolation Forest** (Liu, Ting, and Zhou, 2008 and 2012) isolates points with random splits. Anomalies need fewer splits. It catches claims unusual against the whole population, scales linearly, and needs no distance metric.
- **Local Outlier Factor** (Breunig et al., 2000) compares a point's local density with its neighbors'. It catches claims that are unusual for their kind, such as a surgical claim with a medical claim's pattern, even when they are ordinary globally.
The two stay separate, so each can be evaluated and shown on its own. Features are standardized. Isolation Forest has 200 trees. LOF uses 35 neighbors in novelty mode, so that a new claim is scored against the baseline without refitting. Each flags a claim beyond the 99th percentile of its own baseline scores: by construction, about 1 in 100 normal claims is flagged.
Training data is synthetic because there is no real claim data, and real data is prohibited until the privacy gate (D-034). The generator is seeded and documented, and its distributions are judgment, not measurement. The evaluation's anomaly labels are never seen in training. Results on synthetic data say nothing about real-world detection.

### D-084 Detectors are fitted at service start, and checked against a manifest
**Status:** Accepted · **Source:** Dev
Fitting on the 5,000-claim baseline takes about a second. So the AI service fits both detectors at startup from the seeded generator, instead of loading a serialized model. Unpickling executes code, and a pickle ties the model to one library version. The training script writes a committed manifest: generator version, seed, parameters, a fingerprint of the training data, and each detector's threshold. Startup recomputes them and refuses to start on any mismatch, so a change to the generator, the parameters, or a library that alters the model cannot pass unnoticed. Every result records the model version and fingerprint.

### D-085 Risk scoring
**Status:** Accepted · **Source:** User (approach) / Dev (weights)
Severity points: low 5, medium 12, high 25. Category caps: consistency 40, financial 40, duplicate 50, document 10, missing information 15, statistical anomalies 20 (10 for each detector that flags). Bands: `LOW` below 20, `MEDIUM` 20 to 44, `HIGH` 45 or more, with the total capped at 100. The statistical cap is below the `HIGH` threshold, so anomaly detection alone can never place a claim in `HIGH`. It can raise a claim that rules already put in `MEDIUM`, and the score breakdown shows when it did. `HIGH` routes to senior review. Weights are judgment, stored as versioned configuration (`risk-scoring-1`), and the result keeps every version it was computed with. The score is not a probability.

### D-086 The AI summary explains, and decides nothing
**Status:** Accepted · **Source:** User (role) / Dev (checks)
After the screening is stored, the configured model (DeepSeek V4.1 Flash, no new model) may write a summary of up to 900 characters from the de-identified signals: titles, explanations, and contributions, without names. Code keeps it only if it cites signals that exist, uses only numbers that appear in them, and does not assert fraud, guilt, rejection, or a decision. Otherwise the gateway asks the same model for a repair, with up to two more attempts, and then the summary is recorded as unavailable. It lives in its own table, written after the screening, so it cannot change any score, band, or route. The screening is complete without it.

### D-087 Near-identical bills are found by their line items
**Status:** Accepted · **Source:** Dev
The plan named a perceptual page hash, which extraction would have to compute, changing a completed milestone. Instead, a bill is near-identical to another claim's bill when at least four line items were read and 70% of them match on normalized description and amount, at the same hospital. This compares content, survives a re-typed or re-scanned copy, and needs only what extraction already stores. A copy that is wholly re-written is not caught. A perceptual hash can be added later.

### D-088 Demonstration scenarios are seeded synthetic claims
**Status:** Accepted · **Source:** User (need) / Dev (design)
The seed adds a synthetic policy, earlier claims (submitted, so they are processed when core starts), and three draft claims, with generated documents. A demonstrations page, available only in the local environment, explains each scenario's embedded signals, and submits its draft through the normal submission. It waits until the earlier claims are processed, because duplicate checks compare against their extracted facts. Nothing is faked: each claim is extracted, consolidated, analyzed, and screened like any other. A scenario runs once per database. A reset restores it.

### D-089 scikit-learn and NumPy
**Status:** Accepted · **Source:** Dev
scikit-learn 1.9.1 and NumPy 2.5.4, the current releases, with Windows wheels for Python 3.14, checked on PyPI on 2026-11-08. scikit-learn's implementations of both detectors are reference implementations of the papers. A few lines of NumPy would not be credible replacements.

### D-090 Risk screening evaluation
**Status:** Accepted · **Source:** Dev (measured)
On 2026-11-08, on 2,000 held-out synthetic normal claims and 210 controlled synthetic anomalies of 7 types: Isolation Forest 0.9% false positives, 47.6% recall, ROC AUC 0.902; LOF 1.1%, 93.3%, 0.995; either detector 1.8% and 94.8%. Isolation Forest misses inflated claims entirely, because almost every baseline claim asks for exactly its bill. That is a known limit of axis-parallel splits, and is kept visible in a test; rule FIN-03 covers those claims. The three demonstrations give HIGH (100), LOW (0), and HIGH (57, rules alone 37), as designed. The summary on DeepSeek V4.1 Flash passed every check in 10 of 10 runs, 5 after one repair, for $0.006.
These results describe agreement with a synthetic generator. They are evidence that the implementation works as designed, and no evidence of how it would detect anything in real claims.


## Milestone 8

### D-091 Review actions are single transactions, and review never runs processing
**Status:** Accepted · **Source:** Dev
A `review` module owns tasks, notes, requests, findings, and outcomes. Each action commits the record, the task change, the claim's status (through the claim module's `ClaimReview`), the audit event, and any outbox event together. Review never calls the workflow: an outcome sends `CLAIM_DECIDED`, which ends it, and a request to analyze corrected facts sends `CLAIM_REANALYSIS_REQUESTED`, which the workflow treats as changed documents. A request for information needs no signal: intake's new documents already restart processing. The workflow only opens a task when a claim is ready, and cancels it when processing starts again. ArchUnit keeps review away from Temporal and the AI client.

### D-092 Task routing, one live task, and the authority limit
**Status:** Accepted · **Source:** User (rules) / Dev (design)
A ready claim goes to an investigator first if its latest screening is `HIGH` and it has no investigation finding yet; else to senior review above the reviewer authority limit; else to review. After a finding, confirmed fraud and large claims go to senior review, others to review. Escalation only goes up. The limit is configuration (`PRISM_REVIEWER_AUTHORITY_LIMIT`, INR 3,00,000 by default), a project setting, not a regulatory figure. A claim has at most one live task, which the database enforces with a partial unique index. Taking a task is a conditional update, and "take next" uses `SKIP LOCKED`, so two people never hold the same claim. Every action names the task it acts on, and is refused if the task has changed. Investigations and escalations show the claim as `ESCALATED`.

### D-093 Outcomes, separation of duties, and revision
**Status:** Accepted · **Source:** User (principle) / Dev (rules)
Approved pays the claimed amount, partly approved a smaller one, denied nothing, each with a rationale of at least 10 characters and the reviewer's view of the AI analysis, recorded for evaluation. An outcome stores the consolidation, analysis, and screening it was made with. Outcomes are insert-only. A senior reviewer revises one with a new record that supersedes it, and someone other than its author must do so. Nobody takes or decides a claim they created, and refusals are audited as denied. Investigators record findings, never outcomes: confirmed fraud exists only as an investigator's finding, and a reviewer still decides.

### D-094 Fact corrections are versions applied at consolidation
**Status:** Accepted · **Source:** Dev
A correction is an insert-only row naming the fact it corrects. The newest correction for a fact applies at the next consolidation: the fact becomes `PRESENT` with the corrected value and a link to the correction, and keeps its document sources, so the screen still shows what each document said. Validation, analysis, and screening then use the corrected value. Analyzing again is the reviewer's choice, and never automatic. Audit records the field corrected, never the value.

### D-095 The evidence chain is assembled from records, not copied
**Status:** Accepted · **Source:** Dev
The chain reads, in order: outcomes, the path through the queues, findings and requests, the analysis and screening the outcome was made with, that consolidation's facts and source pages, the AI runs, and the documents with their SHA-256. Nothing is copied into a report that could drift from its source. Claim intelligence provides its part through `ReviewSupport`. Auditors and claim readers see it. Administrators do not.

### D-096 Duplicate indicators are grouped per document (ruleset `risk-rules-2`)
**Status:** Accepted · **Source:** Dev (found by testing)
Version 1 of the duplicate rules raised one indicator per matching claim. M8's end-to-end run found a claim whose bill was reused in 19 earlier test claims: 19 indicators filled the duplicate category's cap, made the claim high risk, and sent it to investigation, on the strength of one reused file. Version 2 raises at most one indicator per document (DUP-01 to DUP-03), or one per claim (DUP-04, DUP-05), and lists every matching claim as evidence. A reused file is one signal however often it recurs, which is what the category caps were meant to ensure (D-085). The demonstrations' results do not change: each of their matches involves a single other claim.

## Presentation readiness

### D-097 A read-only overview endpoint for the home page
**Status:** Accepted · **Source:** User (presentation)
The home page shows open claims, claims with reviewers, claims waiting for information, decisions, risk bands, open review work by queue, and the six newest claims. `GET /api/v1/overview` (`CLAIM_READ`) returns these counts from SQL in a small `overview` module, rather than the web page calling several list endpoints and counting pages of results. Drafts are counted only for staff with `CLAIM_MANAGE`, the same rule as the claim list. Staff without `CLAIM_READ` see the page's links and roles, without figures.

### D-098 Presentation data is loaded through the public API, not seeded
**Status:** Accepted · **Source:** User (presentation)
`prism.py demo-prepare` publishes every policy and loads 25 synthetic claims (`test-data/claims/demo-corpus/`) as staff would: it signs in as each synthetic role, uploads, submits, waits for real processing, and works each claim through review according to its plan. Seeding through services, as the earlier seeders do, would skip extraction and screening, and the point of this data is to show what they produce. Values a policy manager would correct (a "Nil" deductible, a policy period read as a duration) are corrected with a stated reason, never confirmed as read. The three hand-uploaded cases in `demo/` depend on earlier claims in this set. A claim loaded from an older version of the set is withdrawn as entered in error and loaded again, since withdrawn claims are never compared against.

### D-099 Reasoning is set per route, and budgeted where it stays on
**Status:** Accepted · **Source:** Dev (found by evaluation)
The gateway never stated whether a model should reason, so DeepSeek V4.1 Flash reasoned by default, and reasoning tokens count against `max_tokens`. On policy extraction it spent the whole 16,000-token budget thinking and returned nothing, on the first attempt and on the repair, so every policy was read by the escalation model, and a failed escalation failed the policy. The agent's 1,500- and 3,000-token budgets were cut off on two answers in five, and one in five went to the escalation model, so results varied with which issues landed there.
Every route now states `reasoning`, and the gateway sends it on every request. Reading and copying tasks (classification, extraction, reread, policy extraction) and the risk summary run without it: code checks their output against the page or the signals. The Policy Agent keeps it, with 6,000 tokens to plan and 12,000 to analyze: without reasoning, one run in two obeyed a document's injected "cite nothing". Its analysis prompt moves to `agent_analysis.v2`, which says that a limit that applies is LIMITED even when only arithmetic, which Prism does, would show it is exceeded.
The agent evaluation's injection measure now also counts a missing expected finding as steering (the "cite nothing" case), and its forbidden finding is limited to coverage and exclusion, so an incidental supported condition is not counted.

### D-100 Presentation data uses realistic invented names, without watermarks
**Status:** Accepted · **Source:** User (presentation)
For the presentation, the user asked that nothing in the running system or its documents say the data is synthetic. Every person, hospital, insurer, and policy number is still invented, and the data is still synthetic: only its labels change. People have ordinary Indian names, hospitals have invented local names chosen to avoid real ones (so that no real hospital is shown with risk indicators), the insurer is "Navdhara General Insurance Ltd", policy numbers start `NGI-`, and staff accounts use `@prism.local`. Documents carry no watermark or "fictional" footer, and the interface shows no "synthetic data" badge.
This changes no rule: real claim data remains prohibited until the privacy gate in security.md is cleared, and the documentation and evaluation reports still say the data is synthetic. Internal names (seeder classes, the `SYNTHETIC_USER_SEEDED` audit action, which is part of the hash chain, and the anomaly generator `synthetic-claims-1`) are unchanged. The risk summary prompt moves to `risk_summary.v2`, which describes the detectors' baseline without the word synthetic; the anomaly panel still says the baseline comes from a seeded generator, because that is how the detectors were trained.
