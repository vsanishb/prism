# Fraud and Risk

## Purpose
Identify anomalies and risk signals needing attention, with explanations. This is screening, not a determination of fraud.

## Scope
**In:** rule engine, cross-document consistency, financial anomalies, duplicates, document anomalies, scoring.
**Out:** confirmed fraud determinations, automated rejection, trained statistical models (no suitable data yet).

## Design considerations

**Vocabulary.** Keep these distinct in data and UI: data inconsistency, risk indicator, fraud indicator, and confirmed fraud. Only an investigator can record the last one, and it is a human action.

**Indicators**

| Category | Examples |
|---|---|
| Consistency | Date, patient, or hospital mismatch across documents; treatment outside admission period |
| Financial | Line items not matching totals; repeated line items; claim above bill or policy limit; unusual amount |
| Duplicate | Same file hash; repeated invoice number with provider; near-identical documents; matching claim details |
| Document | Missing expected fields; unusual structure; inconsistent totals |
| Missing information | Absent required documents or critical fields |

**Each indicator contains:** rule ID and version, category, severity, plain-language explanation, evidence links, and score contribution.

**Duplicate lookups.** Core runs them, since AI cannot see other claims ([D-013](../decisions.md)). It sends only matching signals, and never another claim's content. Exact matches use hashes and normalized invoice keys. Near matches use a perceptual page hash stored at extraction.

**Scoring.** Weighted, capped per category so one noisy area cannot dominate, with bands `LOW`, `MEDIUM`, and `HIGH`. Weights are versioned configuration, and the ruleset version is stored with each result. Initial weights are expert judgment, and the UI says so.

**Unusual amounts.** With no historical data, begin with policy limits and configurable thresholds. Statistical baselines need real volume and are deferred.

**Model use.** Optional semantic contradiction checks must cite both sources, and are labeled as model-suggested ([D-028](../decisions.md)).

**Fairness.** Rules never use protected characteristics, or treat a provider or person as suspicious by identity alone.

**Routing.** `HIGH` sends the review task to an investigator or senior reviewer. No band can change a claim's outcome.

## Dependencies
M3 facts and workflow. Policy limits from M6 where available.

## Components
`risk` package: rule interface, rule sets, scorer. Core `intelligence`: match queries and storage. Web: risk panel with drill-down to evidence.

## Testing
Positive and negative case per rule; seeded anomaly claims; clean claims produce few false positives; deterministic scoring; checks that UI and API text never assert fraud.

## Completion criteria
All seeded anomalies are detected, every indicator links to evidence, and high risk changes routing only.
