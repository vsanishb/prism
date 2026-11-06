# Human Review

## Purpose
Give authorized people everything needed to reach a decision, and keep that authority with them.

## Scope
**In:** review tasks, workspace, information requests, escalation, investigation findings, authorized outcomes, fact corrections, evidence chain.
**Out:** payments and external notifications.

## Design considerations

**Every claim is reviewed.** No path bypasses a human, whatever the risk band.

**Task routing**

| Condition | Goes to |
|---|---|
| Standard claim | `REVIEWER` queue |
| Above reviewer authority limit | `SENIOR_REVIEWER` |
| `HIGH` risk | `FRAUD_INVESTIGATOR` first, then a reviewer |
| Reviewer escalation | `SENIOR_REVIEWER` or `FRAUD_INVESTIGATOR` |

**Workspace.** One screen with the source document beside facts, policy assessment, risk indicators, unresolved questions, and history. Reviewers can move from any statement to its source in one step.

**Actions:** claim task, release task, add note, correct fact, request information, escalate, record outcome.

**Outcomes:** `APPROVED`, `PARTIALLY_APPROVED`, `DENIED`, each with a required rationale. An approved amount is required where relevant.

- Disagreement with AI is normal and needs no special handling beyond the rationale. It is recorded for evaluation.
- Outcomes are immutable. A correction creates a new superseding record, through a permissioned reopen action.
- Fact corrections create versions, and never overwrite. Re-analysis after a correction is a reviewer choice.
- Conflicts are prevented with task ownership and optimistic locking.
- The decision commits first, then the outbox signals the workflow ([flow](../system-flows.md#human-review)).
- Separation of duties is in [security.md](../security.md#authorization).

## Dependencies
M6, M7, and M3 workflow.

## Components
`review` module: tasks, routing, notes, requests, outcomes. Workflow human-wait stages. Web: queue, workspace, decision dialog, evidence chain view.

## Testing
Routing rules; authority limits; separation of duties; concurrent claiming and deciding; duplicate signals; information-request loop; the full journey in Playwright.

## Completion criteria
A claim runs from intake to authorized outcome, and any outcome traces back to its source pages, AI runs, and reviewer rationale.
