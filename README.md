# Prism

Prism is a claims intelligence platform for health insurance. It reads the documents behind a claim, reads the policy it is made under, and gives a human reviewer a structured, evidence-backed view of both: what the documents say, what the policy wording says about it, what looks inconsistent or unusual, and where every one of those statements comes from.

Prism supports decisions. It does not make them. Every claim ends with a person's recorded outcome, and that outcome can be traced back through the analysis to the exact page of the document it rests on.

> **Status.** Milestones 1 to 8 are complete: the full path from intake to an authorized decision works through the web interface, and is covered by automated tests. Milestone 9 (production readiness: deployment, monitoring, hardening) has not started. All data in this repository is synthetic. Prism has not been used with real claims, and must not be until the privacy requirements in [docs/security.md](docs/security.md) are met.

## Contents

- [What it does](#what-it-does)
- [How it is built](#how-it-is-built)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Running the tests](#running-the-tests)
- [Configuration and secrets](#configuration-and-secrets)
- [Documentation](#documentation)
- [Limitations](#limitations)

## What it does

| Area | What happens |
|---|---|
| Claim intake | Staff create claims against a policy and upload bills, discharge summaries, prescriptions, and reports. Uploads are validated and stored with a SHA-256 hash. |
| Document intelligence | Each document is classified and its fields extracted by a vision model. Every value is checked against the page's text layer and given a state: present, missing, uncertain, or conflicting. Nothing is guessed. |
| Policy intelligence | A policy wording is read into provisions: benefits, limits, waiting periods, exclusions, definitions, and how they refer to each other. A person confirms every critical value before the version is published. |
| Hybrid retrieval | Published wordings are indexed for semantic, keyword, and exact-clause search, fused and reranked, so the right provision is found together with its exceptions and definitions. |
| Policy analysis | An agent examines the claim issue by issue (cover, waiting periods, exclusions, cost sharing) using only retrieved wording. Code checks every citation against the text, and dates and amounts are calculated by code, not by the model. |
| Risk screening | 22 deterministic rules look for inconsistencies, financial anomalies, and duplicates across claims. Two unsupervised detectors (Isolation Forest and Local Outlier Factor) flag statistically unusual claims. A capped, versioned score sets how closely the claim is reviewed. It never labels a claim fraudulent. |
| Human review | Claims are routed to reviewers, senior reviewers, or investigators. Reviewers can request documents, escalate, correct extracted facts, and record outcomes with a rationale. Separation of duties is enforced on the server. |
| Traceability | Every sensitive action is audited in a tamper-evident log. An evidence chain links each outcome to the people involved, the analysis it was decided with, the facts, their source pages, and every AI call. |

## How it is built

```text
Browser ──▶ Next.js web ──▶ Spring Boot core ──▶ FastAPI AI service ──▶ OpenRouter (models)
                               │        │               │
                           Temporal  Documents     PostgreSQL: search index
                               │
                           PostgreSQL: claims, policies, review, audit
```

- **Core** (Java, Spring Boot) is the system of record. It owns users and permissions, claims, policies, documents, review, audit, and the durable workflows that process claims. It is the only component that accepts user traffic.
- **AI service** (Python, FastAPI) does model work: extraction, policy reading, retrieval, the policy agent, anomaly detection, and summaries. It is stateless for claims: it receives what it needs, returns a result, and keeps nothing. It accepts only signed requests from core.
- **Web** (Next.js) is the interface. It talks only to core.
- **Temporal** runs claim processing as durable workflows, so a restart or an outage loses no work.

| Layer | Technology |
|---|---|
| Core | Java 21, Spring Boot 4.1, Temporal Java SDK 1.40, Flyway, ArchUnit |
| AI service | Python 3.14, FastAPI, Pydantic, scikit-learn, pypdfium2, mypy (strict), ruff |
| Web | Node 25, Next.js 16, React 19, TypeScript, Tailwind CSS 4, Playwright |
| Data | PostgreSQL 18 with pgvector and pg_trgm, run as a project-owned local cluster |
| Models | OpenRouter, routed only to providers with zero data retention |

Versions are pinned, and every choice is recorded with its reasons in [docs/decisions.md](docs/decisions.md).

## Project structure

```text
prism/
├── services/
│   ├── core/            Spring Boot application: the system of record
│   └── ai/              FastAPI service: model work and the search index
├── web/                 Next.js frontend
├── database/
│   └── migrations/      Flyway migrations for every schema (the only place schema changes are made)
├── scripts/             prism.py, the local development tool, and synthetic document generators
├── test-data/           Synthetic documents and policies used by tests and demonstrations
├── demo/                Three claims to upload by hand in a demonstration: blatant, clean, disguised
├── docs/                Architecture, decisions, plans, module guides, and test guides
├── infra/               Deployment configuration (placeholder until Milestone 9)
└── .github/workflows/   Continuous integration
```

### Core (`services/core/src/main/java/dev/prism/core`)

A modular monolith. Each package is a module with a public API, and its `internal` package is private to it. ArchUnit tests enforce the boundaries.

| Package | Responsibility |
|---|---|
| `identity` | Users, invitations, sign-in, sessions, roles, and permissions |
| `audit` | Append-only, hash-chained audit trail |
| `policy` | Policies and their versions |
| `policyknowledge` | Reading a wording into provisions, review, and publication |
| `claim` | Claim lifecycle, intake, and the demonstration scenarios |
| `document` | Upload validation, storage, and page rendering |
| `intelligence` | Extraction results, consolidated facts, policy analysis, and risk screening |
| `risk` | Risk rules, features, and scoring, as pure code with no database access |
| `retrieval` | Search index status and search for core |
| `review` | Review tasks, routing, outcomes, investigation findings, and the evidence chain |
| `workflow` | Temporal workflows, activities, and the transactional outbox |
| `aiclient` | The signed client for the AI service |
| `system`, `platform` | Status checks, configuration, security, and error handling |

### AI service (`services/ai/src/prism_ai`)

| Package | Responsibility |
|---|---|
| `api` | Internal routes, and verification of core's signed requests |
| `gateway` | The only path to models: routing, zero-retention enforcement, output validation, usage records |
| `documents` | Rendering in an isolated process, extraction, and grounding values in the text layer |
| `policies` | Reading policy wordings into provisions |
| `retrieval` | Chunking, embeddings, hybrid search, fusion, and reranking |
| `agent` | The policy agent and its citation checks |
| `risk` | Synthetic baseline, Isolation Forest and LOF, and the checked risk summary |
| `prompts` | Versioned prompt files |

`services/ai/evals/` holds the evaluation sets and scripts that measure each AI component against known answers.

### Web (`web/src`)

| Path | Contents |
|---|---|
| `app/(app)/` | Pages: claims, policies, review, risk demonstrations, audit, users, system status |
| `features/` | Screen components, grouped by area (`claims`, `policies`, `risk`, `review`, …) |
| `components/ui/` | Shared design-system components |
| `lib/` | API clients, types, labels, and formatting |
| `e2e/` | Playwright browser tests, one file per milestone |

## Getting started

These steps set up a complete local environment on **Windows 10 or 11**. Everything runs natively; Docker is not used. The setup script downloads Temporal and builds pgvector for Windows. Other platforms need those two installed by hand.

### 1. Install the prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 (Temurin recommended) | `java -version` should report 21 |
| Python | 3.14 | Then install uv: `python -m pip install uv` |
| Node.js | 25 | Includes npm |
| PostgreSQL | 18 | Only the binaries are used. Prism runs its own cluster on port 5544 and leaves any installed service alone |
| Visual Studio 2022 Build Tools | "Desktop development with C++" workload | Needed once, to build the pgvector extension |
| Git | any recent | |

You also need an [OpenRouter](https://openrouter.ai) API key for the AI features. Usage on the synthetic data is a few cents per claim.

### 2. Get the code and dependencies

```powershell
git clone https://github.com/vsanishb/prism.git
cd prism
cd web; npm install; cd ..
```

Python dependencies are installed automatically by uv the first time a command runs. Maven downloads Java dependencies on the first build.

### 3. One-time setup

Run these from the repository root, one after another:

```powershell
python -m uv run --project services/ai python scripts/prism.py setup
python -m uv run --project services/ai python scripts/prism.py install-pgvector
python -m uv run --project services/ai python scripts/prism.py install-temporal
```

`setup` creates a PostgreSQL cluster under `.data/`, generates every local password and signing key, and writes them to `.env`. Both are excluded from Git. Then open `.env` and set your model key:

```text
OPENROUTER_API_KEY=your-key-here
```

### 4. Create the database

```powershell
python -m uv run --project services/ai python scripts/prism.py db-reset
```

This applies all migrations and loads the synthetic data: users for every role, policies, sample claims, and the risk demonstration claims. Run it again at any time to return to a clean state. It deletes all local data.

### 5. Start Prism

Start each service in its own terminal, from the repository root:

```powershell
python -m uv run --project services/ai python scripts/prism.py run-temporal
```

```powershell
python -m uv run --project services/ai python scripts/prism.py run-ai
```

```powershell
python -m uv run --project services/ai python scripts/prism.py run-core
```

```powershell
python -m uv run --project services/ai python scripts/prism.py run-web
```

Open <http://localhost:3000>. The Temporal console is at <http://localhost:8233>.

After a reboot, start the database first with `scripts/prism.py db-start`.

### Prepare a demonstration (optional)

With all four services running:

```powershell
python -m uv run --project services/ai python scripts/prism.py demo-prepare
```

It publishes every policy and loads 25 claims in every state, using the real model (a few minutes, and well under a US dollar of model calls). It then prints what each claim raised. The three cases in [demo/](demo/README.md) are ready to upload by hand afterwards; each folder's README gives the values to type and what Prism should show.

### 6. Sign in and try it

The synthetic accounts and their passwords are in `.data/synthetic-users.md`, which `db-reset` generates. There is one account per role:

| Account | What to try |
|---|---|
| `claims.processor` | Create a claim and upload documents from `test-data/claims/`. Open **Risk demonstrations** and submit the three prepared scenarios |
| `reviewer` | Open **Review**, take a task, and work it in the review workspace |
| `senior.reviewer` | Decide escalated and high-value claims, and revise outcomes |
| `investigator` | Record findings on high-risk claims |
| `policy.manager`, `policy.approver` | Review a policy wording's extracted values and publish it |
| `auditor` | Read the audit trail and any claim's evidence chain |
| `admin` | Manage users and roles |

The [test guides](docs/test-guides/README.md) walk through each milestone's flows step by step, with the expected result at every step.

## Running the tests

| Suite | Command | Notes |
|---|---|---|
| Core | `cd services/core; ./mvnw test` | Unit, integration on real PostgreSQL, workflow, and architecture tests. Each run uses a disposable database |
| AI service | `cd services/ai; python -m uv run pytest` | Model calls are scripted, so no network or key is needed |
| AI lint and types | `python -m uv run ruff check src tests evals; python -m uv run mypy` | From `services/ai` |
| Web | `cd web; npm run typecheck; npm run lint; npm test` | |
| End to end | See below | |

The browser tests run against a separate, complete copy of Prism with its own ports and database, and a stand-in for the model provider, so they need no API key and cost nothing:

```powershell
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

When it reports that the stack is ready, run from `web` in another terminal:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```

The evaluation scripts in `services/ai/evals/` measure the AI components against the real models, and cost a small amount per run. Results are recorded in the module guides.

## Configuration and secrets

- All configuration comes from environment variables, read from `.env` locally. [.env.example](.env.example) lists every variable, with no values.
- `.env`, `.data/` (database, documents, keys, and generated passwords), and all key files are excluded by `.gitignore`. No credential is committed, and none is hard-coded.
- Core and the AI service authenticate each other with short-lived ES256 tokens, signed with keys that `setup` generates.
- Claim content goes only to model providers that keep no data, and the AI service refuses any route it cannot verify as such.

## Documentation

| Read this | To learn |
|---|---|
| [docs/project-context.md](docs/project-context.md) | What Prism is, its principles, and its vocabulary |
| [docs/architecture.md](docs/architecture.md) | Components, boundaries, and data ownership |
| [docs/system-flows.md](docs/system-flows.md) | How each process runs end to end |
| [docs/decisions.md](docs/decisions.md) | Why each choice was made, and the pinned versions |
| [docs/progress.md](docs/progress.md) | What exists, and the test results for each milestone |
| [docs/roadmap.md](docs/roadmap.md) | Milestones and their checklists |
| [docs/modules/](docs/modules/README.md) | How each built module works |
| [docs/test-guides/](docs/test-guides/README.md) | Step-by-step flows to try in the interface |
| [docs/security.md](docs/security.md), [docs/testing.md](docs/testing.md) | Security model and test strategy |

## Limitations

- **Synthetic data only.** Extraction, retrieval, policy analysis, and risk screening have been evaluated on small synthetic sets. Their results say nothing yet about accuracy on real claims and real policy wordings.
- **The anomaly detectors learned a synthetic baseline.** They show the method working, not real-world fraud detection.
- **Not production ready.** There is no deployment, monitoring, multi-factor authentication, or key rotation yet. These are Milestone 9.
- **Windows-first local setup.** The tooling downloads Windows builds of Temporal and builds pgvector with Visual Studio.
- **India as the primary context.** Amounts are in rupees, and the synthetic policies follow Indian health insurance conventions. Prism makes no regulatory compliance claims.

## License

Released under the [MIT License](LICENSE).
