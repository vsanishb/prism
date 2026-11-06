# Core service

Spring Boot application and system of record. Built so far: `identity`, `audit`, `aiclient`, `system`, and `platform`.

Owns authentication, authorization, claims, policies, documents, reviews, audit, and Temporal workflows. It is the only component that accepts user traffic, reads document storage, or calls the AI service.

Packages under `dev.prism.core`, built and planned:

| Package | Responsibility |
|---|---|
| `identity` | Users, sessions, roles, invitations |
| `audit` | Append-only audit events |
| `policy` | Policy records, versions, publication |
| `claim` | Claim lifecycle and consolidated claim data |
| `document` | Upload, validation, storage, retrieval |
| `intelligence` | AI run records, extraction, assessment, and risk results |
| `review` | Review tasks and authorized outcomes |
| `workflow` | Temporal workflows, activities, and the outbox |
| `aiclient` | Authenticated client for the AI service |
| `system` | Live status checks |
| `platform` | Cross-cutting configuration, errors, observability |

See [architecture](../../docs/architecture.md).
