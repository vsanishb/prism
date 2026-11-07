# M4 Test Guide: Policy Intelligence

Read a policy wording into provisions, confirm its critical values against the source, and publish it. **All data is synthetic.** Real models are called through DeepSeek, at about two to five cents per policy.

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

Open http://localhost:3000. Passwords are in `.data/synthetic-users.md`. There are now two policy managers: `policy.manager` uploads, and `policy.approver` publishes.

## Wordings to use

| File in `test-data/policies/` | Use |
|---|---|
| `family-floater-gold-v1.pdf`, `-v2.pdf` | Seeded on the Gold policy, and read when core starts |
| `family-health-platinum.pdf` | The hard case: a table and a clause crossing page breaks, footnotes, and exceptions |

## Flows

**1. Reading.** As `policy.manager`, open policy `SYN-FFG-2026-0001`. Both versions show **Reading**, then **In review**. Open **Review knowledge**.

**2. Values beside their source.** On **Values to confirm**, choose any value. Its page appears with the value outlined, and the quote above it.

**3. Structure.** On **Structure**, provisions are grouped by type. Exclusions show their **exceptions** separately, and cross-references read like "Conditions in 3.2".

**4. Confirm and correct.** Confirm a value. Correct another: a correction without a reason is refused, and one with a reason is kept with your name.

**5. A check against the register.** Correct the sum insured to a different amount, then try to publish: it is refused, because it no longer matches the policy register.

**6. Separation of duties.** Once every value is reviewed, choose **Publish** as `policy.manager`: it is refused, because you uploaded the wording. Sign in as `policy.approver` and publish.

**7. Immutable once published.** The version shows **Published**, with no review buttons and no option to replace the wording.

**8. The hard case.** Add a version to `SYN-IND-2026-0002` with a sum insured of 750000 and an unused period, and upload `family-health-platinum.pdf`. Check that clause 4.5, which crosses a page break, is whole, and that 2.7 and 2.8 carry their footnote.

**9. Rejection.** Reject a version in review. A reason is required. A new wording can then be uploaded.

**10. Permissions.** As `REVIEWER`, the knowledge is readable, with no review or publish buttons. As `ADMIN`, policies are not visible.

**11. Audit.** As `AUDITOR`, filter by Policy value confirmed and Policy version published.

## Automated runs

```bash
python -m uv run --project services/ai python scripts/prism.py e2e-stack
```

Then, from `web`:

```powershell
$env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
```
