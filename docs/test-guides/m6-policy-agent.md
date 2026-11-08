# M6 Test Guide: Policy Agent

Read a claim against its published policy, issue by issue, with every citation opening at its page of the wording. **All data is synthetic.** Each analysis calls real models through DeepSeek, at about two to three cents a claim.

## Start

Stop any services still running from earlier milestones. Then run each command in its own PowerShell window, from `D:\prism`. The reset deletes your local data, and the new migrations need it.

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

## Flows

**1. Publish the policy first.** Analysis needs the claim's policy version published and searchable. As `policy.manager`, confirm every value of Gold version 1 (`SYN-FFG-2026-0001`), then as `policy.approver` publish it. Wait for **Searchable** on the version page.

**2. Process a claim.** As `claims.processor`, create a claim on `SYN-FFG-2026-0001` with admission 14 March 2026, upload the three files in `test-data/claims/appendectomy/`, and submit. The processing panel now ends with **Reading the policy against the facts**. The claim reaches **In review**.

**3. Read the analysis.** Choose **Read policy analysis**. The page says it is not a claim decision. Each issue (cover, waiting period, exclusions, cost sharing, and any others the plan raised) shows, separately:
- **Claim facts used**, with any that are not established marked.
- **Policy evidence**: each citation, with its quote.
- **Reasoning, by AI**.
- **Calculated by Prism**: inputs, steps, and outcome, for any limit or waiting period that applies.
- **Open questions**.

**4. Citations.** Choose a citation: its page of the wording opens, with the provision outlined.

**5. Unknowns.** Cost sharing usually ends as **Needs interpretation**, because the documents do not give the patient's age. Nothing is guessed.

**6. Checks.** Where Prism changed what the model proposed, a note says what it proposed and why: for example, a quote that did not match the wording.

**7. A policy that is not published.** Process a claim on a policy whose version is still in review. The claim still reaches review. The analysis step shows that the policy is not published. Publish it, then choose **Retry** on the claim: the cycle runs again, and the analysis appears.

**8. Permissions.** `REVIEWER` and other claim readers can read the analysis. `ADMIN` cannot.

**9. Audit.** As `AUDITOR`, filter by Claim sent for policy analysis and Claim policy analyzed. Only IDs are recorded.

## Measuring quality

```bash
python -m uv run --project services/ai python services/ai/evals/run_agent.py
```

It indexes the synthetic wordings in a scratch database and analyzes 13 synthetic claims, about 35 cents in all. It prints expected issues found, unknowns kept when facts are missing, manipulation attempts that succeeded (must be 0), and each scenario's findings.

## Automated runs

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```

The stack uses a stand-in model provider that answers within the agent's contract. It proves the flow and the checks, not the quality of the analysis.
