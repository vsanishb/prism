# Database

Flyway is the only migration tool for every schema, and `migrations/` is the only place schema changes are made. Migrations run as a dedicated step using the owner role. Runtime roles have no DDL rights.

Naming: `V<yyyymmddhhmm>__<summary>.sql`. Applied migrations are immutable.

No migrations exist yet. See [architecture](../docs/architecture.md#data-architecture).
