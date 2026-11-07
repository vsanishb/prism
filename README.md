# Prism

Prism is an AI-assisted Health Insurance Claims Intelligence Platform. It turns claim documents and insurance policies into structured, evidence-backed assessments, and leaves consequential decisions with authorized human reviewers.

> **Status:** Milestones 1 to 4 (secure platform, claims and documents, document intelligence, policy intelligence) are complete. See [docs/progress.md](docs/progress.md). Development uses synthetic data only.

## Run it

You need Java 21, Python 3.14, Node 25, and PostgreSQL 18 installed. Everything runs locally, with no hosted database. From M5, Visual Studio 2022 Build Tools (C++) are needed to build pgvector: run `scripts/prism.py install-pgvector` once. The [test guides](docs/test-guides/README.md) have the setup steps and flows to try.

## Repository layout

| Path | Purpose |
|---|---|
| [`services/core`](services/core/README.md) | Spring Boot application: business logic, auth, audit, Temporal workflows |
| [`services/ai`](services/ai/README.md) | FastAPI service: document intelligence, RAG, Policy Agent, risk analysis |
| [`web`](web/README.md) | Next.js frontend |
| [`database`](database/README.md) | Flyway migrations for all schemas |
| `scripts` | Local development tool and synthetic document generator |
| [`test-data`](test-data/README.md) | Synthetic documents |
| [`infra`](infra/README.md) | Deployment configuration |
| [`docs`](docs/README.md) | Architecture, plans, roadmap, decisions, and progress |

## Where to start

- What Prism is: [docs/project-context.md](docs/project-context.md)
- How it is built: [docs/architecture.md](docs/architecture.md)
- What happens next: [docs/roadmap.md](docs/roadmap.md)
- Development standards: [CLAUDE.md](CLAUDE.md)
