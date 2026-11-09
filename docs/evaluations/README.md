# Evaluations

One short report per component that has accuracy or efficiency to measure, on synthetic data with known
answers. Regenerate with `python -m uv run python evals/write_reports.py` from `services/ai`, after
running the evaluations (each report names its command).

| # | Report | Component | Run | Verdict |
|---|---|---|---|---|
| 1 | [Document classification and claim extraction](01-claim-extraction.pdf) | AI service: documents pipeline | 09 Nov 2026, 00:55 UTC | All targets met |
| 2 | [Policy wording extraction](02-policy-extraction.pdf) | AI service: policy knowledge | 09 Nov 2026, 00:57 UTC | 1 of 4 targets missed |
| 3 | [Policy search (retrieval)](03-policy-search.pdf) | AI service: retrieval | 09 Nov 2026, 01:00 UTC | All targets met |
| 4 | [Policy analysis agent](04-policy-analysis.pdf) | AI service: policy agent | 09 Nov 2026, 01:07 UTC | 2 of 5 targets missed |
| 5 | [Risk rule engine](05-risk-rules.pdf) | Core: risk module, ruleset risk-rules-2 | 08 Nov 2026, 17:00 UTC | All targets met |
| 6 | [Statistical anomaly detectors](06-anomaly-detectors.pdf) | AI service: risk (Isolation Forest and LOF) | 09 Nov 2026, 01:01 UTC | All targets met |
| 7 | [Risk summary (language model)](07-risk-summary.pdf) | AI service: risk summary | 09 Nov 2026, 01:01 UTC | All targets met |
| 8 | [End-to-end processing and review routing](08-pipeline-routing.pdf) | Core: workflow and review | 08 Nov 2026, 17:00 UTC | All targets met |

Targets are in [testing.md](../testing.md). Every number describes synthetic data: it shows that the system
works as designed, not how it would perform on real claims.
