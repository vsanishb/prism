# Disguised claim

Consistent documents, but the bill is a light copy of an earlier one.

**Expected:** risk band **HIGH (MEDIUM from the rules alone)**, routed to the **Investigation queue**.

## 1. Create the claim

Sign in as `claims.processor@prism.local`, open **Claims**, and choose **New claim**. Enter exactly:

| Field | Value |
|---|---|
| Claim type | Reimbursement |
| Policy | NGI-FFG-2026-0001 (Family Floater Gold) |
| Claimant | Rajesh Iyer |
| Patient | Raghav Hegde |
| Hospital | Kadal Seaview Hospital, Mangaluru |
| Admission date | 05/10/2026 |
| Discharge date | 07/10/2026 |
| Claimed amount | INR 3,82,650.00 |

## 2. Upload the documents

| File | Document type |
|---|---|
| `claim-form.pdf` | Claim Form |
| `hospital-bill.pdf` | Hospital Bill |
| `discharge-summary.pdf` | Discharge Summary |

Then choose **Submit for processing**. Processing takes one to three minutes with the real model.

## 3. What Prism should find

- Every document is consistent. Nothing on the page looks wrong.
- The bill is a light edit of the same patient's bill from 18 days earlier (claim for a cholecystectomy, loaded by demo-prepare): five of seven lines match exactly.
- Same patient, same hospital, similar amount, inside 30 days: a similar claim.
- The statistical detectors find the claim unusual (a large surgical claim, a third claim in six months).

Rules expected to fire:

- `DUP-03` A near-identical bill is in another claim
- `DUP-05` A similar claim shortly before or after

## 4. What to show

Open the **Risk** tab and show the score breakdown: the rules alone give MEDIUM, and the two anomaly detectors add the points that make it HIGH. This is the case the ML layer exists for.

The person who created a claim can never review it. Sign in as `reviewer`, `senior.reviewer`, or `investigator` to work it.
