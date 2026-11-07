# Policy Knowledge

Turns a policy wording into validated, human-approved knowledge. Core: `dev.prism.core.policyknowledge`. AI: `services/ai/src/prism_ai/policies`. Design: [plan](../plans/policy-intelligence.md).

## Flow

1. Uploading a wording sets the version to `PROCESSING` and writes an outbox event in the same transaction.
2. `PolicyIngestionWorkflow` reads the wording in batches of up to six pages, overlapping by one page.
3. The AI service returns provisions with typed values, exceptions, and references. A value is `PRESENT` only if its quote is on the page and the value is inside the quote.
4. Core assembles the batches, finds relations, validates, and opens the version for review.
5. A policy manager confirms each critical value, or corrects it with a reason.
6. A different policy manager publishes. A published version is final.

## Knowledge status

`NO_WORDING` → `PROCESSING` → `IN_REVIEW` → `PUBLISHED` or `REJECTED`. A rejected version can take a new wording, and a published one cannot change ([D-030](../decisions.md)).

## Key types

| Type | Purpose |
|---|---|
| `PolicyAssembler` | Pure. Batches, merging across overlaps, joining cut clauses, relations |
| `PolicyValidator` | Pure. Checks, and the rule each value must meet, also applied to corrections |
| `KnowledgeStore`, `KnowledgeReader` | Insert-only storage, and the review view |
| `PolicyKnowledgeService` | Review, publication, rejection, and retry |
| `PolicyKnowledgeLifecycle` | In `policy`: the version's status changes, each audited |

**Relations** come from three sources: the model's citations, marked when the text does not state them; `Clause x.y` citations code finds in the text; and defined terms used in other provisions.

## Findings

| Code | Severity |
|---|---|
| `PAGES_NOT_READ`, `SUM_INSURED_DIFFERS_FROM_REGISTER`, `LIMIT_EXCEEDS_SUM_INSURED`, `PERCENTAGE_OVER_100` | Error |
| `VALUE_UNCERTAIN`, `PROVISION_TEXT_UNVERIFIED`, `EXCEPTION_NOT_CAPTURED`, `WAITING_PERIOD_WITHOUT_DURATION`, `BENEFIT_WITHOUT_LIMIT`, `UNRESOLVED_REFERENCE`, `NUMBERING_GAP` | Warning |
| `REFERENCE_NOT_IN_TEXT` | Info |

## Publication blockers

- A critical value (an amount, percentage, or duration) that nobody has confirmed or corrected.
- Unread pages.
- A value that breaks a rule, judged on its corrected value where there is one.
- The publisher uploaded the wording.

The database refuses a review of a published or rejected version, as a second line behind the service.

## Endpoints

Under `/api/v1/policies/{policyId}/versions/{versionId}/knowledge`. Reading needs `POLICY_READ`, and everything else needs `POLICY_MANAGE`.

| Route | Purpose |
|---|---|
| `GET` | Status, batches, provisions with values and reviews, relations, findings, blockers |
| `POST /values/{valueId}/review` | `CONFIRMED`, or `CORRECTED` with a value and a reason |
| `POST /publication`, `POST /rejection` | Publish, or reject with a reason |
| `POST /retry` | Retry failed batches |

## Evaluation

Golden set: `services/ai/evals/policy_extraction.json`, with three synthetic policies. One has a benefits table and a clause crossing page breaks, merged cells, footnotes, and exceptions.

On DeepSeek V4.1 Flash, 2026-11-07: 49 of 49 critical values, 53 of 53 provisions, 8 of 8 exceptions, 11 of 11 references, 2 of 2 footnotes, at $0.06 for the set ([D-070](../decisions.md)). The same set run through the browser on the real model gave 26 critical values on the Platinum policy, each linked to its source.

## Not built

- Scanned policies: values are marked uncertain for review, with no second reading.
- Cross-batch joining is tested with scripted results. The real policies so far fit one batch.
- Search over published knowledge arrives in M5.
