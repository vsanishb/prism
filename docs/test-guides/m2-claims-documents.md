# M2 Test Guide: Claims and Documents

How to try Milestone 2 through the UI. Everything is synthetic, and every document is watermarked.

Start Prism as described in the [M1 guide](m1-secure-platform.md#start-prism). If you have run an earlier milestone, first restart your services and rebuild the data:

```bash
python -m uv run --project services/ai python scripts/prism.py db-reset
```

## Synthetic data

**Seeded claims**

| Claim | State | Use it to see |
|---|---|---|
| `CLM-2026-000001` | Submitted | Fixed policy version, four documents, activity |
| `CLM-2026-000002` | Withdrawn | A closed claim |
| `CLM-2026-000003` | Draft | A two-page TIFF, ready to submit |
| `CLM-2026-000004` | Draft | No documents, so not ready to submit |

**Seeded policies:** `SYN-FFG-2026-0001` has two versions with wordings, and `SYN-IND-2026-0002` has one version with no wording.

**Files to upload,** in [`test-data/`](../../test-data/README.md):

| Folder | Contents |
|---|---|
| `claims/appendectomy/` | Claim form, hospital bill, discharge summary |
| `claims/scanned/` | PNG, JPEG, and a two-page TIFF |
| `policies/` | Two policy wordings |
| `invalid/` | Files that must be refused |

Sign in with accounts from `.data/synthetic-users.md`.

## Flows to try

### 1. Create and submit a claim (claims processor)

1. Open **Claims**, choose **New claim**, and submit the empty form. **Expect:** errors beside each field.
2. Enter a discharge date before the admission date. **Expect:** a clear refusal.
3. Create the claim with policy `SYN-FFG-2026-0001` and admission date 14 March 2026. **Expect:** Version 1 shown for that date, and "Add at least one document".
4. Upload `claims/appendectomy/hospital-bill.pdf`. **Expect:** it appears, and the claim is ready to submit.
5. Submit. **Expect:** details lock, and the version becomes "Applicable version".

### 2. Hostile and invalid files (claims processor)

Upload each file from `test-data/invalid/` to a draft claim.

| File | Expect |
|---|---|
| `spoofed-text-named-as.pdf`, `spoofed-html-named-as.png` | Not a supported type, despite the name |
| `truncated.pdf` | Damaged |
| `password-protected.pdf` | Password protected |
| `empty.pdf` | Empty |
| `unsupported-office-file.docx` | Not supported |

**Expect also:** "Upload refused" in the claim's activity. Uploading the same valid file twice is refused too.

### 3. View documents

1. Open `discharge-note.tiff` on `CLM-2026-000003`. **Expect:** two pages, with next, previous, and zoom.
2. Download the original. **Expect:** the same file, saved rather than opened.

### 4. Replace, remove, and withdraw (claims processor)

1. On a draft, replace a document. **Expect:** the old one stays, marked Replaced.
2. Remove a document. **Expect:** it stays on record, marked Removed.
3. Withdraw a claim. **Expect:** a reason is required, and the claim then closes.

### 5. Access by role

| Sign in as | Expect |
|---|---|
| Reviewer | Can read `CLM-2026-000001` but change nothing. Drafts are not listed, and opening one by URL shows "This could not be found" |
| Auditor | Can read claims, and in **Audit trail** can filter on "Document page viewed" |
| Administrator | No Claims or Policies. `/claims` shows "You do not have access to this page" |

### 6. Policy register (policy manager)

1. Register a policy, and add a version for 2026.
2. Add an overlapping version. **Expect:** a clear refusal.
3. Upload a PNG as the wording. **Expect:** only PDFs are accepted.
4. Upload `policies/family-floater-gold-v2.pdf` and view it. **Expect:** two pages.

## Automated checks

The large upload tests need files too big to commit:

```bash
python -m uv run --project services/ai python scripts/generate_test_data.py --oversized
```

With a freshly reset database and all services running, after `npm run build --prefix web`:

```bash
npm run e2e --prefix web
```
