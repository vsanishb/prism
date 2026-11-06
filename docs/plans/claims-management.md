# Claims Management

## Purpose
Manage the claim lifecycle, policy association, and secure document intake.

## Scope
**In:** policy and version records, claims, parties, documents, status, activity timeline, seed data.
**Out:** extraction (M3), policy processing (M4), decisions (M8).

## Design considerations
- States and allowed transitions are in [system-flows.md](../system-flows.md#claim-states). One `ClaimStateMachine` enforces them.
- Use optimistic locking on claims, to detect concurrent edits.
- The policy version is selected by treatment date, and fixed on the claim when submitted.
- Only `DRAFT` claims can be freely edited. Later additions are recorded events.
- Documents are immutable: replacements create a new record that supersedes the old.
- Submission requires the minimum data only. Completeness is assessed later, and missing items are a visible result rather than a blocker.
- A file's SHA-256 is stored at upload, supporting integrity and duplicate detection.
- Upload rules are in [security.md](../security.md#file-upload-security).

## Dependencies
M1 (identity, audit, shell).

## Components
| Component | Responsibility |
|---|---|
| `policy` module | Policies and version metadata |
| `claim` module | Lifecycle and state machine |
| `document` module | Validation, storage, retrieval |
| `DocumentStore` | Storage port with a filesystem adapter |
| Web | Claims list, creation, detail, upload, viewer, policy register, timeline |
| Seed tool | Generates synthetic policies, claims, and PDFs |

## Testing
Every state transition, valid and invalid; concurrent edits; upload attack cases; object-level access; filter and pagination behavior; Playwright intake flow.

## Completion criteria
A claim can be created, linked to the correct policy version, supported by validated documents, submitted, and traced on its timeline, with permissions enforced.
