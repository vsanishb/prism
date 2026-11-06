# Deployment and Infrastructure

## Purpose
Make Prism simple to run locally and safe to run when deployed.

## Scope
**In:** local environment, CI, containers, environments, secrets, observability.
**Out:** multi-region operation.

## Design considerations

### Local development (M1, M3)
- Docker Compose runs dependencies: Temporal dev server with UI (M3), plus Postgres as an optional offline database.
- Application services run natively for fast iteration.
- A Neon development branch is the default database. Tests use Testcontainers.
- The Docker image for Temporal uses a persisted database file, so workflows survive restarts.

### Neon specifics
- Use the pooled endpoint for runtime, direct for migrations.
- Keep Hikari pools small, and tolerate cold starts with connection timeouts and retries.
- Use a separate Neon branch per environment, and separate projects for production.

### Deployed environments (M9)
The hosting provider is not yet chosen. The design requires only containers, private networking, and a secret store.

| Component | Target |
|---|---|
| Web | Container or Vercel-compatible hosting |
| Core | Container, always on, because it runs the Temporal worker |
| AI | Container on a private network |
| Temporal | Temporal Cloud or self-hosted with a dedicated database |
| Documents | S3-compatible object storage, encrypted |
| Secrets | Host's secret manager |

## Dependencies
Provider selection needs the user's input before M9.

## Components
Compose file; multi-stage Dockerfiles running as non-root; GitHub Actions pipeline; migration job; health and readiness endpoints; OpenTelemetry configuration; runbooks.

## Testing
CI builds from a clean clone; migrations run against an empty database and a Neon branch; restore from backup is tested; staging runs the full Playwright suite.

## Completion criteria
**M1:** a new developer can start everything from the README.
**M9:** staging is deployed from CI, with secrets managed, health monitored, and restore proven.
