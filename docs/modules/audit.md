# Audit

Tamper-evident record of important actions. Package: `dev.prism.core.audit`. Design: [plan](../plans/audit-logging.md).

## Public API

| Type | Use |
|---|---|
| `AuditRecorder.record` | Inside a business transaction. Fails if there is none |
| `AuditRecorder.recordIndependently` | Own transaction, for events that must survive a rollback |
| `AuditEntry` | Builder for an event |
| `AuditAction`, `AuditDetail` | Closed sets. Add values here when a module needs them |

## Chains

Each event belongs to a stream (`identity` today, and one per claim or policy later). Writers hold a transaction-scoped advisory lock per stream, and each event's hash covers its fields plus the previous hash.

**`AuditHash` must never change its serialization.** Doing so would make all stored events fail verification.

## Protection

The runtime role has no `UPDATE`, `DELETE`, or `TRUNCATE` grants. A trigger also blocks the owner role. Chains make any change that bypasses both detectable.

## Endpoints

`GET /api/v1/audit/events` and `POST /api/v1/audit/verification`, both requiring `AUDIT_READ`. Verification reads every event, and is itself audited.

## Behavior to know

- Details accept only `AuditDetail` keys, which keeps personal data out.
- Failed sign-ins for unknown accounts record no email.
- Anonymous denied requests are not audited, so that they cannot flood the trail.
- The viewer joins `core.users` for actor names. Names are not copied into events.

## Testing

`AuditIntegrityTest` covers immutability, rollback, concurrency, tamper detection, role isolation, and content checks.
