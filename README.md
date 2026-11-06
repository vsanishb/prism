# Prism

Prism is an AI-assisted Health Insurance Claims Intelligence Platform. It turns claim documents and insurance policies into structured, evidence-backed assessments, and leaves consequential decisions with authorized human reviewers.

> **Status:** planning foundation only. No application code exists yet. See [docs/progress.md](docs/progress.md).

## Repository layout

| Path | Purpose |
|---|---|
| [`services/core`](services/core/README.md) | Spring Boot application: business logic, auth, audit, Temporal workflows |
| [`services/ai`](services/ai/README.md) | FastAPI service: document intelligence, RAG, Policy Agent, risk analysis |
| [`web`](web/README.md) | Next.js frontend |
| [`database`](database/README.md) | Flyway migrations for all schemas |
| [`infra`](infra/README.md) | Local Docker Compose and deployment configuration |
| [`docs`](docs/README.md) | Architecture, plans, roadmap, decisions, and progress |

## Where to start

- What Prism is: [docs/project-context.md](docs/project-context.md)
- How it is built: [docs/architecture.md](docs/architecture.md)
- What happens next: [docs/roadmap.md](docs/roadmap.md)
- Development standards: [CLAUDE.md](CLAUDE.md)

Setup instructions will be added in Milestone 1, when there is something to run.
