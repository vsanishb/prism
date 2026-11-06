# Temporal Workflows

## Purpose
Run long claim and policy processes durably, across failures and human waits.

## Scope
**In:** workflow definitions, activities, outbox relay, stage reporting, retries, human waits.
**Out:** the business logic inside each stage, which lives in its own module.

## Design considerations
- Workflows contain orchestration only, and all I/O lives in activities.
- Workflow code stays deterministic. Changes use `Workflow.getVersion` patching, and replay tests protect active workflows.
- Payloads carry IDs only ([D-011](../decisions.md)).
- Activities are idempotent, using deterministic keys so that retries are safe.
- The AI service classifies errors. `4xx` and validation failures are non-retryable; `429`, `5xx`, and timeouts retry with backoff.
- Fan-out is bounded to protect model rate limits.
- `FAILED` stages wait for a retry signal instead of ending the workflow, so a claim is never lost to a transient outage.

### Task queues
| Queue | Work |
|---|---|
| `prism-workflows` | Workflow tasks |
| `prism-activities-db` | Short database activities |
| `prism-activities-ai` | AI calls, with their own concurrency limit |

### Signals and queries
| Signal | Effect |
|---|---|
| `documentsAdded` | Resume from the information wait |
| `retryStage(stage)` | Retry a failed stage |
| `reviewCompleted(decisionId)` | Complete, deduplicated by decision ID |
| `informationRequested(requestId)` | Move to the information wait |
| `claimWithdrawn` | Close the claim |

Long histories use `continueAsNew` if repeated request cycles grow them.

### Timeouts (initial)
| Activity | Start-to-close | Retries |
|---|---|---|
| Database | 30 seconds | Up to 5 |
| Document extraction | 5 minutes | Up to 4, with backoff |
| Policy page batch | 5 minutes | Up to 4, with backoff |
| Policy Agent | 10 minutes, with heartbeat | Up to 3 |
| Risk assessment | 2 minutes | Up to 4 |
| Information wait | Configurable durable timer | n/a |

## Dependencies
M2 claims; AI endpoints added per milestone; [D-004](../decisions.md) gate.

## Components
`workflow` module: workflow interfaces and implementations, activity adapters, `OutboxRelay`, `StageRecorder`. The workflow grows by one stage per milestone (M3, M6, M7, M8).

## Testing
Time-skipping tests for waits; failure and retry; duplicate signals; replay tests from saved histories; outbox relay crash recovery; worker restart mid-activity.

## Completion criteria
Claims survive restart of any service, failures are visible and retryable, and every stage transition is recorded.
