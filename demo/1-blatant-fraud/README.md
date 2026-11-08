# Blatant claim

Plainly inconsistent, with a bill copied from another claim.

**Expected:** risk band **HIGH**, routed to the **Investigation queue**.

## 1. Create the claim

Sign in as `claims.processor@synthetic.prism.test`, open **Claims**, and choose **New claim**. Enter exactly:

| Field | Value |
|---|---|
| Claim type | Reimbursement |
| Policy | SYN-FFG-2026-0001 (Family Floater Gold) |
| Claimant | Synthetic Policyholder One |
| Patient | Synthetic Patient Aditya |
| Hospital | Synthetic Crescent Hospital, Visakhapatnam (Fictional) |
| Admission date | 12/10/2026 |
| Discharge date | 13/10/2026 |
| Claimed amount | INR 10,60,000.00 |

## 2. Upload the documents

| File | Document type |
|---|---|
| `claim-form.pdf` | Claim Form |
| `hospital-bill.pdf` | Hospital Bill |
| `itemized-invoice.pdf` | Itemized Invoice |
| `discharge-summary.pdf` | Discharge Summary |

Then choose **Submit for processing**. Processing takes one to three minutes with the real model.

## 3. What Prism should find

- `hospital-bill.pdf` is byte-for-byte the bill from claim *Synthetic Patient Kiran* (loaded by demo-prepare). It names Kiran, not Aditya, and different dates.
- `itemized-invoice.pdf` reuses Kiran's invoice number at the same hospital, is dated five days before the admission, repeats the ICU line, and its items do not add up to its total.
- INR 10,60,000 is claimed for one night: more than both bills and more than the INR 10,00,000 sum insured.

Rules expected to fire:

- `DUP-01` The same file is in another claim
- `DUP-02` An invoice number is reused at the same hospital
- `CON-01` The patient differs across documents
- `CON-03` The dates of stay differ across documents
- `CON-04` A bill is dated before the admission
- `FIN-01` Bill items do not add up to the total
- `FIN-02` An item is repeated on a bill
- `FIN-03` The claim is above the bills
- `FIN-04` The claim is above the sum insured
- `FIN-06` A very high amount per day of stay

## 4. What to show

Open the claim's **Risk** tab: every indicator links to its page. Then sign in as the investigator to show the claim waiting in the investigation queue. Point out that Prism says *for review*, never *fraud*.

The person who created a claim can never review it. Sign in as `reviewer`, `senior.reviewer`, or `investigator` to work it.

All names, hospitals, and amounts are fictional.
