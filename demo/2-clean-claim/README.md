# Clean claim

An ordinary, consistent claim.

**Expected:** risk band **LOW**, routed to the **Review queue**.

## 1. Create the claim

Sign in as `claims.processor@prism.local`, open **Claims**, and choose **New claim**. Enter exactly:

| Field | Value |
|---|---|
| Claim type | Reimbursement |
| Policy | NGI-FFG-2026-0001 (Family Floater Gold) |
| Claimant | Rajesh Iyer |
| Patient | Ananya Iyer |
| Hospital | Patravali Hospital, Mysuru |
| Admission date | 05/10/2026 |
| Discharge date | 08/10/2026 |
| Claimed amount | INR 96,870.00 |

## 2. Upload the documents

| File | Document type |
|---|---|
| `claim-form.pdf` | Claim Form |
| `hospital-bill.pdf` | Hospital Bill |
| `discharge-summary.pdf` | Discharge Summary |

Then choose **Submit for processing**. Processing takes one to three minutes with the real model.

## 3. What Prism should find

- Every document agrees with the others and with the form.
- The bill adds up, and the claim asks for exactly the bill.
- No other claim shares a file, an invoice number, or a bill with it.

Rules expected to fire:

- None

## 4. What to show

Show the policy analysis (appendicitis is covered under Clause 2.1), the empty indicator list, and the claim waiting in the ordinary review queue. A LOW band changes nothing but how closely it is reviewed.

The person who created a claim can never review it. Sign in as `reviewer`, `senior.reviewer`, or `investigator` to work it.
