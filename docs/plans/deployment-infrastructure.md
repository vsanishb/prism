# Deployment and Infrastructure

## Purpose
Make Prism simple to run locally and safe to run when deployed.

## Scope
**In:** local environment, CI, environments, secrets, observability.
**Out:** multi-region operation.

## Design considerations

### Local development (M1, M3)
- A project-owned PostgreSQL cluster serves local development and tests ([D-046](../decisions.md)).
- Application services run natively for fast iteration.
- PostgreSQL 18 is the only database, run locally ([D-074](../decisions.md)). pgvector is built natively by `scripts/prism.py install-pgvector`, which needs Visual Studio 2022 Build Tools with the C++ workload.
- Temporal runs natively from `scripts/prism.py`, with a persisted database file so that workflows survive restarts.
- Docker is not used ([D-056](../decisions.md)).

### Deployed environments (M9)
The hosting provider and packaging are not yet chosen, and need the user's decision. Containers require explicit approval ([D-056](../decisions.md)). The design requires private networking and a secret store.

| Component | Target |
|---|---|
| Web | Node or Vercel-compatible hosting |
| Core | Always on, because it runs the Temporal worker |
| AI | Private network only |
| Temporal | Temporal Cloud or self-hosted with a dedicated database |
| Documents | S3-compatible object storage, encrypted |
| Secrets | Host's secret manager |

## Dependencies
Provider selection needs the user's input before M9.

## Components
Native local tooling; GitHub Actions pipeline; migration job; health and readiness endpoints; OpenTelemetry configuration; runbooks.

## Testing
CI builds from a clean clone; migrations run against an empty database; restore from backup is tested; staging runs the full Playwright suite.

## Completion criteria
**M1:** a new developer can start everything from the README.
**M9:** staging is deployed from CI, with secrets managed, health monitored, and restore proven.
