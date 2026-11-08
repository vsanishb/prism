# Risk Screening

Screens each processed claim for inconsistencies, risk indicators, and statistical anomalies, and sets how closely it is reviewed. It never determines fraud and never rejects a claim. Design: [plan](../plans/fraud-risk.md), [D-082](../decisions.md) to [D-090](../decisions.md).

## Flow

1. The claim workflow's `RISK_SCREENING` stage runs after policy analysis, on the latest consolidation.
2. Core loads the facts, documents, findings, policy version, and matches in other claims (`RiskInputLoader`).
3. Rules run in core (`risk` module). Features are computed in core.
4. The AI service scores the nine features with Isolation Forest and LOF (`POST /internal/v1/claims/risk/anomaly`).
5. Core scores, stores, and audits, in one transaction.
6. If any signal was raised, the AI service writes a summary (`POST /internal/v1/claims/risk/summary`). Core stores it separately, or the reason it is unavailable.

Like analysis, screening never holds review: a failure is shown with its reason, and **Retry** runs the cycle again. The change is guarded by `Workflow.getVersion("risk-screening")`.

## Core

| Package or type | Purpose |
|---|---|
| `risk` | Pure: no database, no other modules (ArchUnit enforces it). `RuleEngine`, `RiskFeatures`, `RiskScorer`, `ScoringConfig`, vocabulary enums |
| `risk.internal` | The rules, by category, and normalization of names and invoice numbers |
| `resources/risk/risk-scoring-1.json` | Versioned weights, checked at startup |
| `intelligence.internal.RiskScreeningStep` | The stage: load, rules, anomaly call, score, store, summary |
| `RiskInputLoader` | Facts and cross-claim matches, by SQL. Drafts and withdrawn claims are never compared against |
| `RiskStore`, `RiskReader`, `RiskController` | Insert-only storage, and `GET /api/v1/claims/{id}/risk` (`CLAIM_READ`, claim visibility) |
| `claim.internal.Demonstration*` | Seeded scenarios and `GET /api/v1/demonstrations/risk`, local environment only (D-088) |

**Ruleset `risk-rules-1`** (22 rules). Each indicator has a rule ID and version, category, kind, severity, explanation without names, evidence, and points.

| Category | Rules |
|---|---|
| Consistency | CON-01 patient differs, CON-02 hospital differs, CON-03 stay dates differ, CON-04 bill dated before admission, CON-05 discharge before admission, CON-06 document dated far outside the stay |
| Financial | FIN-01 items do not add up, FIN-02 item repeated on a bill, FIN-03 claim above the bills (over 1%), FIN-04 above the sum insured, FIN-05 at 90% to 100% of it, FIN-06 over INR 2,50,000 a day |
| Duplicate (fraud indicators) | DUP-01 same file in another claim, DUP-02 invoice number reused at the same hospital, DUP-03 near-identical bill, DUP-04 overlapping stay for the same patient, DUP-05 similar claim within 30 days |
| Document | DOC-01 read type differs, DOC-02 bill missing number, date, or total, DOC-03 bill with no items |
| Missing information | MIS-01 required document missing, MIS-02 critical facts not established |

Duplicate rules compare only the same policy's claims for patients (DUP-04, DUP-05, prior claims), and every claim for files and bills. Evidence names the other claim's number; opening it applies that claim's access rules.

**Features `risk-features-1`:** claimed amount, share of sum insured, nights, amount per day, bill line items, largest item's share, claimed against the bills, the patient's earlier claims in a year, days since cover start. A value the facts cannot give is sent as null and replaced by the baseline median, which the result says.

**Scoring `risk-scoring-1`:** see [D-085](../decisions.md). The result stores the ruleset, scoring, feature, and model versions, the model fingerprint, each category's points and cap, each detector's result, and the rules-only score.

## AI service

| Module | Purpose |
|---|---|
| `risk/synthetic.py` | Seeded generator: 5,000 baseline claims, 2,000 held-out normal claims, 210 labeled anomalies in 7 types |
| `risk/features.py` | Feature list and transforms (log for amounts and days) |
| `risk/detectors.py` | Fitting, scoring, percentiles, and the manifest check. Fitted at startup in about 3 seconds |
| `risk/anomaly-1.manifest.json` | The recorded fingerprint and thresholds. Startup refuses a mismatch |
| `risk/evaluation.py` | Held-out evaluation. The only place labels are used |
| `risk/summary.py`, `prompts/risk_summary.v1.md` | The summary and its checks |
| `api/risk.py` | The two endpoints, signed calls only |

## Evaluation

All on synthetic data, on 2026-11-08. Reproduce with `python -m uv run python evals/train_risk_models.py` (no cost) and `evals/run_risk_summary.py` (real model).

| Detector | False positives on held-out normal claims | Recall on controlled anomalies | ROC AUC |
|---|---|---|---|
| Isolation Forest | 0.9% | 47.6% | 0.902 |
| Local Outlier Factor | 1.1% | 93.3% | 0.995 |
| Either | 1.8% | 94.8% | n/a |

By anomaly type, Isolation Forest catches early large claims (87%), above-sum-insured medical claims (93%), and the subtle combination (97%), and misses inflated claims (0%): nearly every baseline claim asks for exactly its bill, so its random splits rarely isolate that feature. LOF catches all seven types at 73% to 100%. Rule FIN-03 covers inflated claims deterministically. The script writes full results to `services/ai/evals/results/risk_anomaly.json`, which is not committed.

**Summary, real model (DeepSeek V4.1 Flash):** 10 of 10 runs produced a summary that passed every check, 5 after one repair, for $0.006. A later run of 6 passed every check at the first attempt, so the cause of those repairs was not isolated.

**Read these with care.** The detectors learned the generator's idea of normal, and are evaluated on the generator's idea of anomalous. These numbers show the implementation works as designed. They say nothing about detecting fraud in real claims.

## Not built

- Confirmed fraud and investigation records (M8, by people only), and the review queue that acts on the route (M8).
- Perceptual page hashes for near-identical scans ([D-087](../decisions.md)).
- Semantic contradictions proposed by a model ([D-028](../decisions.md)).
- Training on real claims. The privacy gate in [security.md](../security.md) applies first.
