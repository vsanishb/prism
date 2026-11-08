# Human Review

Gives authorized people everything needed to decide a claim, and keeps the decision with them. Design: [plan](../plans/human-review.md), [D-091](../decisions.md) to [D-095](../decisions.md).

## Flow

1. Processing ends with `readyForReview`. The workflow's activity then calls `ReviewQueue.openTask`, which routes the task (below).
2. A person takes the task from **Review** (`/review`) or the claim's workspace (`/claims/{id}/review`).
3. They decide, or send the claim elsewhere:
   - **Record outcome:** the claim is `DECIDED`, and `CLAIM_DECIDED` ends the workflow.
   - **Request information:** the claim is `AWAITING_INFORMATION`. Intake's new documents restart processing, and a new task opens with the request marked fulfilled.
   - **Escalate:** the task moves to senior review or investigation, with the reason shown first.
   - **Correct a fact, then analyze again:** `CLAIM_REANALYSIS_REQUESTED` runs a new cycle that applies the correction.
4. An investigator's finding sends the claim back to reviewers. A senior reviewer can revise an outcome with a superseding record.

## Routing

| Condition | Queue | Claim status |
|---|---|---|
| Latest screening `HIGH`, not yet investigated | Investigation | `ESCALATED` |
| Claimed above the authority limit (INR 3,00,000 by default) | Senior review | `IN_REVIEW` |
| Otherwise | Review | `IN_REVIEW` |
| After a finding: confirmed fraud or above the limit | Senior review | `IN_REVIEW` |
| Reviewer escalation | Senior review or investigation | `ESCALATED` |

## Permissions

| Permission | Roles | Allows |
|---|---|---|
| `REVIEW_QUEUE` | Reviewer, senior reviewer | Tasks in the review queue |
| `SENIOR_REVIEW_QUEUE` | Senior reviewer | Senior tasks, and amounts above the limit |
| `INVESTIGATION_QUEUE` | Fraud investigator | Investigation tasks and findings |
| `OUTCOME_RECORD` | Reviewer, senior reviewer | Outcomes, fact corrections, analyzing again |
| `OUTCOME_REVISE` | Senior reviewer | Revising someone else's outcome |

Nobody takes or decides a claim they created. Refusals are audited as `ACCESS_DENIED`.

## Core

| Type | Purpose |
|---|---|
| `review.ReviewQueue` | For the workflow: open and cancel tasks. Idempotent |
| `TaskRouting` | Pure routing and escalation rules |
| `ReviewTaskStore` | Tasks, with conditional updates, row locks, and `SKIP LOCKED` |
| `ReviewRecordStore` | Notes, requests, findings, and outcomes. All insert-only except requests |
| `ReviewQueueService`, `ReviewActionService` | Every action, each in one transaction |
| `ReviewWorkspaceReader` | A claim's review state and the actions the current user may take |
| `EvidenceChainReader` | The evidence chain |
| `claim.ClaimReview` | Status changes for review, inside the caller's transaction |
| `intelligence.ReviewSupport` | Current records, fact corrections, and evidence |

**API:** `GET /api/v1/review/tasks`, `POST /api/v1/review/tasks/next`, `POST /api/v1/review/tasks/{id}/claim|release`, `GET /api/v1/claims/{id}/review`, `GET /api/v1/claims/{id}/evidence-chain`, and under `/api/v1/claims/{id}/review/`: `notes`, `information-requests`, `information-requests/{id}/withdrawal`, `escalation`, `investigation-findings`, `outcome`, `outcome/revision`, `fact-corrections`, `reanalysis`.

**Data:** `review_tasks`, `review_notes`, `information_requests`, `investigation_findings`, `authorized_outcomes`, `fact_corrections`, and `claim_facts.correction_id` (`V202611080002`).

## Web

| Screen | Purpose |
|---|---|
| `/review` | Queue counts, take next task, your tasks, waiting tasks |
| `/claims/{id}/review` | The workspace: documents beside facts, policy analysis, risk screening, open questions, outcomes, findings, history, and notes. Every fact and indicator opens its source page |
| `/claims/{id}/evidence` | The evidence chain, with source pages |
| Claim page | Review summary, and an open request for intake |

## Not built

- Payments and external notifications (out of scope).
- Assignment by a supervisor, workload balancing, and service-level timers.
- Reopening a decided claim for new documents. A revision corrects the outcome only.
