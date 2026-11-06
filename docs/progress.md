# Progress

The factual record of project state. Update after meaningful work. Newest entries go first in the log.

**Status terms:** `Planned` · `In progress` · `Implemented` (code exists) · `Tested` (verified at the required levels) · `Deferred`

## Current state

**Phase:** M0 complete. M1 not started.
**Application code:** none. No service, database, or UI exists yet.

| Capability | Status | Milestone |
|---|---|---|
| Documentation foundation | Implemented | M0 |
| Authentication, RBAC, audit | Planned | M1 |
| Service-to-service authentication | Planned | M1 |
| Design system and app shell | Planned | M1 |
| Claims, policies, documents | Planned | M2 |
| Temporal workflows | Planned | M3 |
| Document intelligence | Planned | M3 |
| Policy intelligence | Planned | M4 |
| Hybrid retrieval | Planned | M5 |
| Policy Agent | Planned | M6 |
| Fraud and risk | Planned | M7 |
| Human review | Planned | M8 |
| Production readiness | Planned | M9 |

## Open items

| Item | Owner | Needed by |
|---|---|---|
| Verify Boot 4.1.2 + Temporal 1.40 ([D-004](decisions.md)) | Dev | Start of M1 |
| Install Node 24 LTS (25.7 installed) | User | M1 frontend |
| Install uv | Dev or user | M1 |
| Provide Neon project details | User | M1 |
| Confirm internal-users-only scope ([D-031](decisions.md)) | User | M2 |
| Confirm target jurisdiction ([D-035](decisions.md)) | User | M2 |
| Provide OpenRouter key, set account to ZDR only | User | M3 |
| Create a Git remote | User | M1 CI |

## Log

### 2026-11-06: M0 Project foundation

**Implemented:** repository structure, `CLAUDE.md`, the documentation system, architecture, flows, security, testing, roadmap, decisions, and module plans.

**Verified:**
- Installed toolchain: Java 21.0.12, Python 3.14.3, Node 25.7.0, Docker 29.2.1, Git 2.56.
- Current versions checked against package registries.
- Planned Python dependencies resolved on 3.14 with wheels only (`pip --dry-run`).
- OpenRouter models and ZDR endpoints checked against its public API.
- Neon extension availability checked against its docs; `pg_search` found retired.

**Not verified:**
- Boot 4.1.2 with Temporal 1.40: the spike was blocked by local permission policy.
- No model has been run; all routes in [D-026](decisions.md) are provisional.
- Neon and Temporal have not been connected.
