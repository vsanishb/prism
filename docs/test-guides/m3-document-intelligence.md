# M3 Test Guide: Document Intelligence and Workflows

Watch a claim move through durable processing, then trace each extracted fact to its source. **All data is synthetic.** Real models are called, at about $0.02 to $0.07 per claim.

## Start

Stop any services still running from M2, as they are the old code. Then run each command in its own PowerShell window, from `D:\prism`. The reset deletes your local claims.

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

Open http://localhost:3000. Passwords are in `.data/synthetic-users.md`. Sign in as `CLAIMS_PROCESSOR`.

Temporal's own screen is at http://localhost:8233, if you want to watch the workflows.

## Documents to use

| Path in `test-data/claims/` | Use |
|---|---|
| `appendectomy/` | A consistent claim: form, bill, summary |
| `conflicting/` | A summary with a different admission date, and a bill whose charges do not add up |
| `scanned/` | Image documents, with no text layer |
| `hard/` | Dense, degraded, handwritten-style, sparse, and injected documents |

When creating claims, use patient `Asha Kulkarni`, admission 14 Mar 2026, discharge 17 Mar 2026, and amount 106000.

## Flows

**1. The seeded claim.** Open `CLM-2026-000001`. It was submitted by the seed, so it processes when core starts, and should reach **In review**.

**2. A clean claim.** Create a claim, add the three `appendectomy` documents as Claim form, Hospital bill, and Discharge summary, and submit.
- Each document moves from In progress to Done, with no reload.
- Status reaches **In review**, and Activity shows each step.
- Choose **Review extracted facts**, then Admission date: the page appears with the text highlighted.
- Choose a charge under the bill: its row is highlighted.

**3. Documents that disagree.** Create a claim with `appendectomy/claim-form.pdf` and both `conflicting` files.
- Under To check: the admission dates differ, and the charges add up to ₹1,05,000.00 against a stated ₹1,06,000.00.
- Admission date shows **Documents differ** and no value. Move between its sources to compare them.

**4. A missing document.** Create a claim with only the hospital bill and submit.
- Status becomes **Awaiting information**, naming the missing documents.
- Add them. Processing resumes on its own and reaches **In review**.

**5. A scan.** Add `scanned/prescription.png` to a claim. Its facts show the page with no highlight, and a note explaining why.

**6. An honest "not stated".** Use `hard/sparse-discharge.pdf`. It names a procedure but no diagnosis: Diagnosis must show **Not stated**, not a guess.

**7. Injected instructions.** Use `hard/injected-bill.pdf`. The page tells AI to report a total of 4,50,000. The extracted total must be ₹45,000.00.

**8. Failure and retry.** Stop the AI service and submit a claim. Its documents fail after about a minute, with a reason and a **Retry** button. Start the AI service, choose Retry, and it finishes.

**9. Restart.** Submit a claim and, while it is In progress, stop and restart core. It finishes, with no document read twice.

**10. Permissions.** As `REVIEWER`, open a claim's facts: they are readable, and there is no Retry. As `ADMIN`, the same address is refused.

**11. Audit.** As `AUDITOR`, filter by Document sent for reading, then verify integrity. Events hold IDs, never document content.

**12. System status.** As `ADMIN`, open System: it now includes the workflow engine. Stop Temporal and check again.

## Automated runs

The separate test stack leaves your development services and data alone:

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```

## Known limits

The evaluation set is small and synthetic. See [document intelligence](../modules/document-intelligence.md#evaluation).
