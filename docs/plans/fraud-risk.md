# Fraud and Risk

## Purpose
Identify inconsistencies, risk indicators, and statistical anomalies that need a person's attention, with explanations. This is screening, not a determination of fraud.

## Scope
**In:** rule engine; cross-document consistency; financial anomalies; duplicates across claims; document anomalies; missing information; two unsupervised anomaly detectors (Isolation Forest and Local Outlier Factor) trained on a synthetic baseline; scoring and bands; an optional AI-written summary of the signals; review routing; three ready-made demonstration scenarios.
**Out:** confirmed fraud determinations; automated rejection; supervised models; any model trained on real claims (there are none yet); the review task queue itself (M8).

## Design considerations

**Vocabulary.** Five terms stay distinct in data, API, UI, and text. Every rule indicator is one of the first three, and the detectors produce the fourth. The system never produces the fifth.

| Term | Meaning | Produced by |
|---|---|---|
| Data inconsistency | Documents, or documents and entered details, disagree | Rules |
| Risk indicator | A pattern that needs attention | Rules |
| Fraud indicator | A pattern that may warrant investigation. Not a finding of fraud | Rules, mainly duplicates |
| Statistical anomaly | Unusual compared with the baseline population | Isolation Forest, LOF |
| Confirmed fraud | A finding recorded by an investigator after investigation | People only (M8) |

**Pipeline**

```text
Claim facts, documents, policy, claim history (core)
        │
  Feature engineering (core, deterministic)
        │
   ┌────┴─────────────────────┐
Rules (core)          Anomaly detection (AI service)
   │                    ├─ Isolation Forest: unusual against all claims
   │                    └─ LOF: unusual against similar claims
   └────┬─────────────────────┘
  Aggregation (core): capped categories, versioned weights
        │
  LOW / MEDIUM / HIGH → review routing
        │
  Optional AI summary of the signals (cannot change the result)
```

**Rules.** In core, beside the data they read ([D-082](../decisions.md)). Each indicator has rule ID and version, category, kind, severity, plain-language explanation, evidence links, and score contribution.

| Category | Rules |
|---|---|
| Consistency | Patient, hospital, or stay dates differ across documents; bill dated before admission; discharge before admission; a dated document far outside the stay |
| Financial | Line items not matching the total; repeated line items; claim above the bills; claim above, or close to, the sum insured; unusually high amount per day |
| Duplicate | Same file in another claim; invoice number reused at the same hospital; near-identical bill; same patient with an overlapping stay; a similar claim shortly before |
| Document | Read type differs from the declared one; a bill missing expected fields; a bill with no line items |
| Missing information | Required documents absent; critical facts not established |

**Duplicate lookups.** Core runs them in the database, since the AI service cannot see other claims ([D-013](../decisions.md)). Exact matches use SHA-256 and normalized invoice keys. Near matches compare bills' line items: a perceptual page hash would need a change to extraction, so it is deferred ([D-087](../decisions.md)). A match names the other claim's number and what matched, never that claim's content.

**Anomaly detection** ([D-083](../decisions.md), [D-084](../decisions.md)). Nine numeric features that Prism computes for every claim: claimed amount, amount relative to sum insured, length of stay, amount per day, line item count, largest line item's share, claimed amount against the bills, the patient's earlier claims in the past year, and time since cover began. Both detectors are fitted on a reproducible synthetic baseline. The evaluation set's labels are never used in training. A detector flags a claim beyond its 99th percentile of baseline scores. Its score is an anomaly measure, never a probability of fraud.

**Scoring** ([D-085](../decisions.md)). Points by severity, capped per category, plus a capped statistical contribution. Weights are versioned project configuration set by judgment, and the result stores the ruleset, scoring, feature, and model versions. The statistical cap is below the `HIGH` threshold, so anomalies alone can reach `MEDIUM` at most.

**AI summary** ([D-086](../decisions.md)). The configured model (DeepSeek V4.1 Flash) may write a short summary from the de-identified signals only. Code checks that it cites real signals, uses only numbers present in them, and never asserts fraud or a decision. It is stored apart from the screening and cannot change it. Without it, the screening is complete.

**Fairness.** Rules and features never use protected characteristics, and never treat a person or provider as suspicious by identity alone. A provider is compared only through its own documents.

**Routing.** `HIGH` routes the claim to senior review or investigation. No band changes a claim's outcome or status. M8 builds the queue that acts on the route.

**Demonstrations** ([D-088](../decisions.md)). In the local environment, a page offers three seeded synthetic claims to submit: a severe-risk case, a clean case, and a plausible case with subtle signals. The earlier claims they are compared with are seeded and processed first. Everything runs through the normal pipeline.

## Dependencies
M3 facts and workflow; M6 workflow stage pattern. Policy sum insured and cover start from M4.

## Components
Core `risk`: rules, features, scorer, and versioned configuration, with no database. Core `intelligence`: loading, history matches, storage, and the API. AI `risk`: synthetic baseline, detectors, and summary. Web: risk screening page, processing step, demonstrations page.

## Testing
Positive and negative case per rule; clean claims produce no indicators; deterministic, reproducible scoring; detectors train, score, and are evaluated on held-out normal claims and labeled synthetic anomalies; the summary cannot alter the result; UI and API text never asserts fraud.

## Completion criteria
The three demonstrations produce `HIGH`, `LOW`, and `HIGH` through the frontend; every indicator links to evidence; anomaly evaluation targets are met on held-out synthetic data; high risk changes routing only.
