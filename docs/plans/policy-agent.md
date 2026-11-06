# Policy Agent

## Purpose
Analyze a claim against its policy and produce an evidence-backed assessment. It assists human reviewers, and is not a decision-maker.

## Scope
**In:** issue planning, bounded retrieval, structured analysis, citation validation, deterministic calculation.
**Out:** final decisions, risk scoring.

## Design considerations

**Separation of knowledge.** Output keeps four categories distinct: claim facts, policy evidence, reasoning, and unknowns.

**Process** ([flow](../system-flows.md#policy-agent))
1. Plan which issues apply: benefits, limits, waiting periods, exclusions, conditions, cost sharing, required documents.
2. Retrieve evidence per issue with read-only tools: `search_policy`, `get_provision`, `get_related_provisions`, `get_definition`.
3. Stop when evidence is sufficient, or at the configured limit (initially 6 rounds, with a token budget).
4. Produce a structured finding per issue.
5. Validate and calculate.

**Finding schema (per issue)**

| Field | Content |
|---|---|
| `issue` | What was examined |
| `status` | `SUPPORTED`, `LIMITED`, `POTENTIALLY_EXCLUDED`, `INSUFFICIENT_EVIDENCE`, or `NEEDS_HUMAN_INTERPRETATION` |
| `claim_facts` | Fact IDs used |
| `evidence` | Citations: chunk ID and verbatim quote |
| `reasoning` | Short explanation linking facts to evidence |
| `unresolved` | Open questions |

Statuses are deliberately not approve or deny.

**Citation validation.** Each chunk ID must be in this run's retrieved set, and each quote must match its source. A failing finding becomes `INSUFFICIENT_EVIDENCE`, with the failure recorded.

**Deterministic calculation.** The model identifies which limits apply. Code computes applicable amounts ([D-029](../decisions.md)), and shows inputs and steps.

**Uncertainty.** Conflicting provisions are shown side by side and go to `NEEDS_HUMAN_INTERPRETATION`. Missing or conflicting facts are carried forward, never assumed.

**Traceability.** Each run stores the prompt version, models, queries, evidence, and tool calls.

## Dependencies
M3 facts; M5 retrieval; M3 workflow.

## Components
`policy_agent`: planner, tool layer, analyzer, citation validator, calculator. Core `intelligence` stores assessments and citations. Web: assessment view, citation panel.

## Testing
Scripted runs for loop limits and tool failures; invalid citation rejection; missing-evidence behavior; prompt injection through documents and policy text; evaluation scenarios for waiting periods, sublimits, exclusions with exceptions, definitions, and conflicts.

## Completion criteria
Targets in [testing.md](../testing.md#testing-ai-components) are met, every displayed citation opens at its source, and no output presents itself as a claim decision.
