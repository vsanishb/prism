# Workflows

Durable claim processing. Package: `dev.prism.core.workflow`. Design: [plan](../plans/temporal-workflows.md).

## `ClaimProcessingWorkflow`

One workflow per claim, with ID `claim-<id>`. It carries IDs only.

```
begin → extract pending documents → consolidate and validate
  a stage failed       → wait for retry, new documents, or withdrawal
  documents missing    → AWAITING_INFORMATION, until documents arrive or the timer ends
  otherwise            → policy analysis → IN_REVIEW, reopening if documents are added
                         a failed analysis does not hold review; a retry runs the cycle again (D-080)
```

The workflow stays open at `IN_REVIEW` until withdrawal. M8 adds the review outcome.

| Signal | Sent when |
|---|---|
| `documentsChanged` | A document is added or replaced on a submitted claim |
| `retryRequested` | `POST /api/v1/claims/{id}/processing/retry`, needing `CLAIM_MANAGE` |
| `claimWithdrawn` | A submitted claim is withdrawn |

## Key types

| Type | Purpose |
|---|---|
| `ClaimProcessingWorkflowImpl` | Orchestration only. Must stay deterministic |
| `ClaimActivitiesImpl`, `ExtractionActivitiesImpl`, `AnalysisActivitiesImpl` | All effects. Every activity is safe to repeat |
| `OutboxRelay` | Delivers events at least once, in order per claim. `Outbox` itself is in `platform` |
| `TemporalWorkerLifecycle` | Keeps trying to start, so core runs while Temporal is down |
| `ProcessingStatusService` | The processing screen. Reads Postgres only ([D-038](../decisions.md)) |

## Behavior to know

- Only this module may use Temporal, and `claim` knows nothing of processing. ArchUnit enforces both.
- **Queues:** `prism-workflows`, `prism-activities-db`, `prism-activities-ai`. Extraction is limited to 4 per worker and 3 per claim.
- **Heartbeats:** extraction, indexing, and analysis beat every 10 seconds, with a 30-second timeout ([D-063](../decisions.md)). Analysis may run 10 minutes.
- **Versions:** `policy-analysis` (M6) adds the analysis stage. `claim-processing-v1.json` is a pre-M6 history, and must keep replaying.
- `PRISM_INFORMATION_WAIT` sets the wait for documents, 14 days by default. After it, the claim goes to review without them.
- **Changing the workflow:** guard flow changes with `Workflow.getVersion`. If `savedHistoryStillReplays` fails, the change would break running claims. Do not simply regenerate the file.

## Testing

| What | Where |
|---|---|
| Flow, failure, retry, waits, signals, replay | `ClaimProcessingWorkflowTest`, on Temporal's test server with time skipping |
| Activities on the real database and API, outbox outages | `ClaimProcessingFlowTest` |
| Killing real processes mid-work | `scripts/chaos_test.py`, on a running `e2e-stack` |

Restart scenarios, all passing on 2026-11-07: core killed during an activity, AI killed during an activity, Temporal down at submission, Temporal killed during a workflow, and all three restarted during a wait. Each claim finished with one extraction per document and no undelivered events.
