# Audit Logging

## Purpose
Keep a trustworthy, tamper-evident record of important actions. Requirements are in [security.md](../security.md#audit-logging).

## Scope
**In:** event model, transactional recording, immutability, hash chains, viewer, verification.
**Out:** external log archival (M9).

## Design considerations
- Audit writes join the business transaction: both commit, or neither does.
- Denied attempts need their own transaction, because the business transaction rolls back.
- A stream is a claim, a policy, or `system`. Each has its own chain to avoid a global lock.
- Writers take a transaction-scoped advisory lock per stream, keeping each chain ordered.
- Hash input uses a canonical serialization, and includes the previous hash.
- Details use allow-listed fields, so personal data cannot leak in by accident.
- Actions use a typed enum so that reporting stays reliable.

## Dependencies
Flyway roles; `identity` for actor context.

## Components
| Component | Responsibility |
|---|---|
| `audit_events` | Append-only table, with a trigger blocking update and delete |
| `AuditRecorder` | The only write API |
| `AuditChainVerifier` | Recomputes chains and reports the first break |
| Audit API | Filtered, paginated reads for auditors |
| Web viewer | Search, filter, event detail, verification result |

## Testing
Immutability under the runtime role; rollback removes the audit event; concurrent writes to one stream keep the chain valid; verifier detects a modified row; sensitive fields are not stored.

## Completion criteria
- Every action listed in `security.md` is audited as its module is built.
- The runtime role cannot alter events.
- Auditors can verify chains from the UI.
