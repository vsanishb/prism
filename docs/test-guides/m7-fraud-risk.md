# M7 Test Guide: Fraud and Risk Intelligence

Every processed claim is screened: deterministic rules, two anomaly detectors (Isolation Forest and Local Outlier Factor), a capped score, a band, and a review route, with an optional summary written by AI. **All data is synthetic.** Screening is risk screening, not a finding of fraud, and it never rejects a claim.

## Start

Stop any services still running from earlier milestones. Then run each command in its own PowerShell window, from `D:\prism`. The reset deletes your local data: M7 adds a migration and new seed data.

```bash
python -m uv run --project services/ai python scripts/prism.py db-reset
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-temporal
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-ai
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-core
```

```bash
python -m uv run --project services/ai python scripts/prism.py run-web
```

Open http://localhost:3000. Passwords are in `.data/synthetic-users.md`.

The AI service fits both detectors when it starts, in a few seconds, and refuses to start if they do not reproduce the recorded manifest. The reset seeds three earlier claims, and core starts processing them as soon as it runs. That uses the real model, for well under a cent.

## The three demonstration flows

No upload is needed. Sign in as `claims.processor` and choose **Risk demonstrations** in the sidebar. The page describes each scenario's embedded signals, and lists the three earlier claims the scenarios are compared with. Wait until all three show **In review**: the page refreshes itself, usually within a minute or two. The **Submit scenario claim** buttons are disabled until then, because the duplicate checks need the earlier claims' facts.

Each scenario runs once per database. To run it again, reset the database.

### Flow 1: severe risk

1. Under **Severe risk**, choose **Submit scenario claim**. The claim page opens, and processing runs. **Reading the policy against the facts** fails with "not published": the demonstration policy has no wording, and screening does not need one ([D-080](../decisions.md)).
2. When **Screening for risk signals** shows **High risk. Senior review or investigation.**, choose **Read risk screening**.
3. Expect:
   - **Screening result:** High risk, 100 out of 100, route **Senior review or investigation**.
   - **Summary, written by AI:** a few sentences citing signals as S1, S2, and so on. Choose a citation to jump to its signal.
   - **Found by rules:** 11 indicators:
     - Consistency: the patient differs across documents; the dates of stay differ; two bills dated before the admission.
     - Financial: the items do not add up to the invoice total; ICU charges repeated; the claim above the bills; above the sum insured; over INR 2,50,000 a day.
     - Duplicates and other claims: the same file in another claim; an invoice number reused at the same hospital. Both are labeled **Fraud indicator** with "It is not a finding of fraud."
   - Choose any evidence with a page: the document page opens on the right, with the text outlined. **Other claim CLM-…** links to the earlier claim, which opens only if you may see it.
   - **Statistical anomaly detection:** both detectors **Statistically unusual**.
   - **How the score was built:** consistency and financial capped at 40, and duplicates at their cap of 50.

### Flow 2: clean claim

1. Under **Clean claim**, submit, wait, and choose **Read risk screening**.
2. Expect **Low risk**, 0, **Standard review**. Then:
   - **No rule raised an indicator**, and **Every rule checked (22 of 22 found nothing)** open, showing each check.
   - Both detectors **Within the normal range**.
   - The baseline comparison shows each value near the middle of the baseline.
   - The summary says there is nothing to summarize.

### Flow 3: disguised risk

1. Under **Disguised risk**, submit, wait, and choose **Read risk screening**.
2. Expect **High risk**, 57, **Senior review or investigation**, and "Rules alone give 37 (medium risk). Statistical anomalies added 20, which moved the claim to a higher band."
3. **Found by rules:** two fraud indicators. A bill closely matches the same patient's bill from three weeks earlier (5 of 7 line items). A similar claim was made 18 days apart. No consistency or financial indicator: every document agrees.
4. **Statistical anomaly detection:** both detectors **Statistically unusual**. In the baseline comparison, the amount per day, the share of the sum insured, and the patient's earlier claims are marked **Unusual**.
5. Open **Technical details, for demonstration** for each detector's score, threshold, and percentile, the model version and fingerprint, and the training data.

What this flow shows: per-document validation finds nothing. Only the comparison with claim history (rules) and with the population (detectors) shows the risk. Anomaly detection alone could not have made it high risk ([D-085](../decisions.md)).

**What was embedded, so the result can be judged honestly.** Severe: a copied bill (same file and invoice number), conflicting patient and dates, a bill dated before admission, items that do not add up, a repeated item, a claim above the bills and the sum insured. Clean: nothing. Disguised: an edited copy of an earlier bill, a similar claim 18 days before, and a combination of a short stay, 78% of the sum insured, and a third claim in a year. Documents: `test-data/claims/risk-demo/`, generated by `scripts/generate_risk_demo.py`.

## Other checks

- **Results on the demonstrations page:** each scenario shows its band, with a link to its screening.
- **Permissions:** `REVIEWER` and other claim readers can read a screening. `ADMIN` cannot. Only claims processors see **Risk demonstrations**, and only in the local environment.
- **Audit:** as `AUDITOR`, filter by **Claim screened for risk** and **Claim risk signals sent for an AI summary**. Only IDs and the band are recorded.
- **Any claim:** every claim you process is screened the same way. A claim uploaded with the same bill as another claim raises **The same file is in another claim**.

## Measuring the detectors and the summary

```bash
python -m uv run --project services/ai python services/ai/evals/train_risk_models.py
```

Checks the fitted models against the manifest, and prints false positives, recall by anomaly type, and ROC AUC on held-out synthetic data. No model calls.

```bash
python -m uv run --project services/ai python services/ai/evals/run_risk_summary.py
```

Writes ten summaries with the real model, for under a cent, and reports how many passed Prism's checks.

## Automated runs

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```

The stack uses a stand-in model provider that answers for the demonstration documents exactly as printed. The rules and both detectors are the real code.
