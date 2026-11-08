# Policy Agent

Reads a claim's facts against its published policy wording, issue by issue, to assist a reviewer. It decides nothing. AI: `services/ai/src/prism_ai/agent`. Core: `dev.prism.core.intelligence` (`PolicyAnalysisStep`, `AssessmentCalculator`, `AssessmentStore`, `AssessmentReader`). Design: [plan](../plans/policy-agent.md), [D-079](../decisions.md) to [D-081](../decisions.md).

## Flow

1. After consolidation and any wait for documents, `ClaimProcessingWorkflow` runs the `POLICY_ANALYSIS` stage.
2. Core checks that the claim's policy version is published and searchable. If not, the stage fails with `policy_not_published` or `policy_not_searchable`, and the claim still goes to review.
3. Core sends de-identified facts, bill lines, the cover start, and the version's confirmed values.
4. The AI service plans the issues, then runs up to three rounds of search and analysis per issue.
5. Code validates every finding. Core checks it again against the published version, then computes with the confirmed values and stores the result.
6. Reviewers read it at `/claims/{id}/assessment`.

A retry, from the processing panel, runs the cycle again: for example, once the policy is published.

## What is sent

Only `admission_date`, `discharge_date`, `claimed_amount`, `diagnosis`, `procedure`, `test_name`, `findings`, and `medicines`, each with its state, plus bill lines. Never names, hospitals, doctors, or policy numbers. The AI service refuses any other fact.

The cover start is the start of the earliest version reached through consecutive versions in the register. A gap restarts cover. The model is also given the time from cover start to admission, computed in code, so that it never does date arithmetic.

## The agent

| Step | Rule |
|---|---|
| Plan | A model proposes up to 8 issues. Code adds cover, waiting periods, exclusions, and cost sharing if missing |
| Search | Each query searches the version's index (M5). Results bring their related provisions. Evidence carries the whole provision text |
| Analyze | A model returns a finding, or asks for up to 3 more searches. Three rounds per issue, and 30 model calls per claim |
| Retry | Each model call gets one retry. An issue that still fails is shown as not analyzed, and the rest stand |

Prompts: `agent_plan.v1`, `agent_analysis.v1`. Document and policy text is fenced as data, with angle brackets neutralized.

## Validation

In the AI service (`agent/validate.py`), then again in core:

- A citation must name evidence retrieved in this run, and quote it word for word (case, spacing, and typographic quotes aside, at least 8 characters). Core checks the provision belongs to the claim's version.
- One failed citation makes an evidenced finding `INSUFFICIENT_EVIDENCE`.
- An evidenced status (`SUPPORTED`, `LIMITED`, `POTENTIALLY_EXCLUDED`) needs at least one citation.
- A fact the finding relies on must be established. A waiting period needs the admission date; cover, exclusions, and waiting periods need a diagnosis or procedure, whether or not the model named them. Otherwise the finding becomes `INSUFFICIENT_EVIDENCE`, with the gap as an open question.
- A value may be applied only from a cited provision, in a role that fits its kind.

What the model proposed is kept when validation changed it, with the reasons, and shown to the reviewer.

## Calculations

`AssessmentCalculator` computes from confirmed values, showing inputs and steps:

| Role | Computes |
|---|---|
| `WAITING_PERIOD` | End date from cover start, and whether admission falls before it |
| `AMOUNT_LIMIT` | The lower of claimed and limit, and any excess |
| `DAILY_LIMIT` | Per day (an amount, or a percentage of sum insured) times days of stay |
| `PERCENT_OF_SUM_INSURED` | The cap |
| `COPAYMENT` | The insured's share of the claimed amount, if it applies |
| `DEDUCTIBLE` | The amount the insured bears first |

An unknown input gives no result, never a guess. Results are computations for a reviewer, not payable amounts.

## Data

`core.policy_assessments` (one per consolidation), `assessment_findings`, `assessment_citations`, `assessment_facts`, `assessment_calculations`. Insert-only. Model usage goes to `core.ai_runs` against the claim. Sending and storing are audited: `CLAIM_SENT_FOR_POLICY_ANALYSIS`, `CLAIM_POLICY_ANALYZED`.

## Endpoint

`GET /api/v1/claims/{id}/assessment` needs `CLAIM_READ` and claim visibility. It returns the latest analysis, or `null`, marked `current: false` if the facts have changed since.

## Evaluation

Scenarios: `services/ai/evals/agent_scenarios.json`, 13 synthetic claims against the retrieval corpus: waiting periods, sub-limits, a daily room limit, exclusions with and without their exception, missing and conflicting facts, and documents that try to steer the result.

```bash
python -m uv run --project services/ai python services/ai/evals/run_agent.py
```

On 2026-11-07, DeepSeek V4.1 Flash: expected issues 10 of 11, unknowns 4 of 4, injection 0 of 1, no failed scenario, citations shown 100% (the model's own 197 of 198), $0.34 for the set. Detail in [D-081](../decisions.md).

## Not built

- The reviewer's decision: M8.
- Facts the documents do not give, such as the patient's age for an age-based co-payment, are left open.
- Which bill lines a daily limit covers is left to the reviewer: line items are not classified.
