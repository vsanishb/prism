# M8 Test Guide: Human Review and Outcomes

Every processed claim becomes a review task, routed by risk and amount. A person decides, and the outcome traces back to its source pages. **All data is synthetic.**

## Start

Stop any services still running. Then run each command in its own PowerShell window, from `D:\prism`. The reset deletes your local data, and M8 adds a migration.

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

Open http://localhost:3000. Passwords are in `.data/synthetic-users.md`. You need several accounts, so use a private window for a second user.

When core starts, the seeded claims are processed and reach review: the M7 demonstration's three earlier claims, and `CLM-2026-000001`. That needs a minute or two.

## Flows

**1. Routing.** Sign in as `reviewer` and open **Review**. You see the review queue's count and its waiting claims. Then sign in as `senior.reviewer`: the senior queue holds the disguised scenario's second earlier claim, which is claimed at ₹3,84,500, above the ₹3,00,000 reviewer authority limit. A reviewer cannot take it.

**2. A high-risk claim goes to an investigator first.** As `claims.processor`, open **Risk demonstrations** and submit **Severe risk**. It reaches **Escalated**, in the investigation queue.
- As `investigator`, open **Review**, take it, and open its workspace. There is no **Record outcome**: investigators record findings.
- Choose **Record finding**, choose **Fraud confirmed by investigation**, and give a rationale.
- The claim moves to senior review, marked **Investigation finished**.
- As `senior.reviewer`, take it, and record **Denied** with a rationale. The claim is **Decided**.

**3. The workspace.** As `reviewer`, take any waiting claim, such as `CLM-2026-000001`. In one screen:
- **Claim facts:** choose **Source** beside a fact, and its page opens on the right with the text outlined.
- **Policy analysis, by AI**, and **Risk screening:** choose **Evidence** beside an indicator.
- **Open questions**, **Review history**, and **Notes:** add a note.

**4. Request information, and resume.**
1. In the workspace, choose **Request information**, tick **Prescription**, write a message, and send. The claim is **Awaiting information**, and the task closes.
2. As `claims.processor`, open the claim. **Information requested** shows the message. Add a prescription, for example `test-data/claims/scanned/prescription.png`. Processing runs again.
3. The claim returns to the queue, marked **Requested information received**. Instead of adding a document, a reviewer can choose **Withdraw request**, and the claim returns to the queue at once.

**5. Correct a fact.**
1. Take the task, and choose the pencil beside a fact, such as Diagnosis. Give a value and a reason.
2. It appears under **Corrections not yet analyzed**: nothing reruns by itself.
3. Choose **Analyze again with corrections**. After processing, the fact shows **Corrected**, and the task returns marked **Analyzed again with corrections**.

**6. Escalate.** On a review task, choose **Escalate**, choose **Senior review**, and give the reason. The senior reviewer sees the reason first.

**7. Record an outcome.**
1. Choose **Record outcome**, then **Approved**, **Partly approved** with an amount below the claim, or **Denied**.
2. Give a rationale, and how the AI analysis compared with your decision.
3. The claim is **Decided**, and its page shows the outcome.

**8. Revise an outcome.** As `senior.reviewer`, open a claim a reviewer decided, and choose **Revise outcome**. The new record supersedes the old one, and both stay on record. You cannot revise an outcome you recorded yourself.

**9. Evidence chain.** As `auditor`, open a decided claim and choose **Evidence chain**. It shows, in order:
1. The outcome.
2. The people and queues.
3. The analysis and screening it was decided with.
4. The facts: choose a source to open its page.
5. Every AI run.
6. The documents, with their SHA-256.

As `admin`, the page is refused.

**10. Separation of duties.** A person who created a claim cannot take or decide it. **Take next task** skips such claims. A user holding both roles sees "You created this claim, so someone else must review and decide it." As `auditor`, filter the audit trail by **Access denied** to see refusals.

**11. Audit.** As `auditor`, filter by **Review task taken**, **Information requested**, **Investigation finding recorded**, **Outcome recorded**, and **Claim fact corrected**. Details hold IDs, queues, and decisions, never fact values.

## Automated runs

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```
