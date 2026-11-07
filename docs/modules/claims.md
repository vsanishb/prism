# Claims

Claim lifecycle and intake. Package: `dev.prism.core.claim`. Design: [plan](../plans/claims-management.md).

## Key types

| Type | Purpose |
|---|---|
| `ClaimStatus` | All states and transitions. The only place they are declared |
| `ClaimEntity` | State changes go through `submit` and `withdraw`, which enforce transitions |
| `ClaimService` | Business rules, permissions, audit. Also the document guard for claims |
| `SyntheticClaimSeeder` | Seed profile only |

## Endpoints

Under `/api/v1/claims`. Reads need `CLAIM_READ`, and writes need `CLAIM_MANAGE`.

| Route | Purpose |
|---|---|
| `GET /`, `GET /{id}`, `GET /{id}/timeline` | List, detail, history |
| `POST /` | Create. Requires an `Idempotency-Key` header |
| `PUT /{id}` | Edit a draft. Requires the current `version` |
| `POST /{id}/submission`, `POST /{id}/withdrawal` | Lifecycle |
| `POST /{id}/documents`, `.../{documentId}/replacement`, `DELETE .../{documentId}` | Documents |

## Behavior to know

- **Drafts belong to intake.** To anyone without `CLAIM_MANAGE` they do not exist: lists omit them, and direct access returns 404.
- **Version fixing.** Before submission, the version shown is a preview. Submission fixes it from the admission date.
- **Submission needs the minimum:** a covering version and one active document. Processing assesses completeness.
- Submitting twice is harmless. Status changes hold a row lock, so uploads cannot race them.
- Documents can be added or replaced until a claim closes, but removed only while it is a draft.
- `availableActions` tells the UI what to offer. The server still enforces every rule.

## Processing

`ClaimProcessingService` moves claims through `PROCESSING`, `AWAITING_INFORMATION`, and `IN_REVIEW` for the [workflow](workflows.md), as the system actor. Its calls are idempotent, and do nothing on a closed claim. Other modules read claims through `ClaimDirectory`. Submission, withdrawal, and document changes write an outbox event in the same transaction.

## Not yet built

`ESCALATED` and `DECIDED` arrive with human review in M8.

## Testing

`ClaimStatusTest` covers all 64 transition pairs. `ClaimLifecycleTest` covers behavior and access.
