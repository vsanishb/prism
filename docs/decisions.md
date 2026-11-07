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
| CI Postgres | PostgreSQL 18 from the PostgreSQL apt repository | CI not yet run (D-056) |
| uv | 0.13.7 | Installed and verified |
| Apache PDFBox | 3.0.8 | Verified: parsing and rendering in tests and E2E |
| ReportLab | 5.1.0 | Dev only. BSD licensed. Generates test documents |

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
**Status:** Provisional · **Source:** Dev
RRF needs no score calibration across search types. Validated by the M5 evaluation.

### D-024 Exact vector search first
**Status:** Provisional · **Source:** Dev
Queries filter to one policy version, usually only hundreds or thousands of chunks, so an exact scan is fast and has perfect recall, avoiding filtered-HNSW recall loss. Add an index if measurements call for it.

### D-025 No agent or RAG framework
**Status:** Accepted · **Source:** Dev
A thin typed gateway and plain pipelines instead of LangChain or LlamaIndex. The core logic is domain-specific, traceability needs full control, and these frameworks add churn and hidden prompts.

### D-026 Model routing
**Status:** Provisional, extraction route superseded by D-057 · **Source:** User (per-task selection) / Dev (choices)
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
**Status:** Accepted · **Source:** User (principle) / Dev (design)
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
