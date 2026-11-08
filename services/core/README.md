# Core service

Spring Boot application and system of record: users and permissions, claims, policies, documents, review, audit, and the Temporal workflows that process claims. It is the only component that accepts user traffic, reads document storage, or calls the AI service.

Run it with `scripts/prism.py run-core`, and test it with `./mvnw test`. See the [repository README](../../README.md#getting-started).

Packages under `dev.prism.core`. Each is a module with a public API, and its `internal` package is private to it, which ArchUnit enforces.

| Package | Responsibility |
|---|---|
| `identity` | Users, invitations, sign-in, sessions, roles, and permissions |
| `audit` | Append-only, hash-chained audit trail |
| `policy` | Policies and their versions |
| `policyknowledge` | Reading a wording into provisions, review, and publication |
| `claim` | Claim lifecycle, intake, and the demonstration scenarios |
| `document` | Upload validation, storage, and page rendering |
| `intelligence` | Extraction results, consolidated facts and corrections, policy analysis, and risk screening |
| `risk` | Risk rules, features, and scoring: pure code, no database |
| `retrieval` | Search index status and search |
| `review` | Review tasks, routing, outcomes, investigation findings, and the evidence chain |
| `workflow` | Temporal workflows, activities, and the transactional outbox |
| `aiclient` | Signed client for the AI service |
| `system`, `platform` | Status checks, configuration, security, and errors |

See [architecture](../../docs/architecture.md) and the [module guides](../../docs/modules/README.md).
