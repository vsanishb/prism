#!/usr/bin/env python3
"""Generates the M7 risk demonstration claims into test-data/claims/risk-demo/. Output is committed.

    python -m uv run --project services/ai python scripts/generate_risk_demo.py

Three scenarios, and the earlier claims they are compared with (D-088):
- SEVERE: plainly inconsistent, with a bill copied from another claim. Expected band HIGH.
- CLEAN: an ordinary, consistent claim. Expected band LOW.
- DISGUISED: every document consistent, but the bill is a lightly altered copy of the same patient's
  bill from three weeks earlier, and the claim is statistically unusual. Expected band HIGH, where
  the rules alone give MEDIUM.

The manifest records each document's fields and line items exactly as printed. The core seeder reads
it to create the claims, and the end-to-end model stand-in reads it to answer as a model would.
Every person, hospital, and policy is invented, with names chosen to avoid real ones.
"""

from __future__ import annotations

import json
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from generate_test_data import BODY, HEADING, INSURER, OUTPUT, TITLE, key_values, table, write_pdf
from reportlab.platypus import Paragraph, Spacer

DIRECTORY = OUTPUT / "claims" / "risk-demo"
POLICY_NUMBER = "NGI-RSK-2026-0003"
CLAIMANT = "Sunita Rao"
LOTUS = "Kamalini Hospital, Nagpur"
RIVERSIDE = "Nilaya Riverside Hospital, Kochi"
GREENFIELD = "Aravalli Greenfield Hospital, Jaipur"


def amount(value: int) -> str:
    """Indian grouping, as Indian bills print it: 3,84,500.00."""
    digits = str(value)
    if len(digits) > 3:
        head, tail = digits[:-3], digits[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        digits = ",".join([*groups, tail])
    return f"{digits}.00"


@dataclass(frozen=True)
class Item:
    description: str
    quantity: int
    rate: int

    @property
    def total(self) -> int:
        return self.quantity * self.rate

    def row(self) -> list[str]:
        return [self.description, str(self.quantity), amount(self.rate), amount(self.total)]

    def manifest(self) -> dict[str, str]:
        cells = self.row()
        return {
            "description": cells[0],
            "quantity": cells[1],
            "unit_price": cells[2],
            "amount": cells[3],
            "quote": " ".join(cells),
        }


@dataclass(frozen=True)
class Bill:
    code: str
    hospital: str
    number: str
    date: str
    patient: str
    admission: str
    discharge: str
    items: tuple[Item, ...]
    # A stated total that differs from the items, for the severe scenario's invoice.
    stated_total: int | None = None
    itemized: bool = False

    @property
    def total(self) -> int:
        return self.stated_total if self.stated_total is not None else sum(item.total for item in self.items)


@dataclass(frozen=True)
class Claim:
    scenario: str
    role: str
    position: int
    patient: str
    hospital: str
    admission: str
    discharge: str
    claimed: int
    diagnosis: str
    procedure: str
    bills: tuple[Bill, ...]
    # Files from another claim, included unchanged: the same bytes, so the same SHA-256.
    copied: tuple[str, ...] = field(default=())

    @property
    def folder(self) -> str:
        return f"{self.scenario.lower()}-{self.role.lower()}-{self.position}"


E1_BILL = Bill(
    "RDM-E1-BILL", LOTUS, "LH-2026-30418", "05/07/2026", "Meenal Deshpande", "01/07/2026", "05/07/2026",
    (
        Item("Room rent (twin sharing)", 4, 5_500),
        Item("ICU charges", 1, 18_000),
        Item("Consultation fees", 1, 9_600),
        Item("Platelet transfusion", 2, 8_500),
        Item("Pharmacy and consumables", 1, 24_300),
        Item("Laboratory investigations", 1, 14_700),
        Item("Nursing charges", 4, 1_750),
    ),
)  # fmt: skip
E3_ITEMS = (
    Item("Room rent (single private)", 3, 8_500),
    Item("Surgeon fee: laparoscopic cholecystectomy", 1, 165_000),
    Item("Anaesthetist fee", 1, 38_000),
    Item("Operation theatre charges", 1, 72_000),
    Item("Surgical consumables and implants", 1, 54_000),
    Item("Pharmacy", 1, 20_500),
    Item("Laboratory and imaging", 1, 9_500),
)

CLAIMS = (
    Claim(
        "SEVERE", "EARLIER", 1, "Meenal Deshpande", LOTUS, "01/07/2026", "05/07/2026", 112_600,
        "Dengue fever with thrombocytopenia", "Platelet transfusion", (E1_BILL,),
    ),
    Claim(
        "DISGUISED", "EARLIER", 1, "Vikram Nair", RIVERSIDE, "12/04/2026", "15/04/2026", 46_800,
        "Viral fever", "Supportive care", (
            Bill("RDM-E2-BILL", RIVERSIDE, "RH-2026-10233", "15/04/2026", "Vikram Nair", "12/04/2026",
                 "15/04/2026", (
                     Item("Room rent (single private)", 3, 6_000),
                     Item("Consultation fees", 1, 6_400),
                     Item("Pharmacy and consumables", 1, 12_900),
                     Item("Laboratory investigations", 1, 9_500),
                 )),
        ),
    ),
    Claim(
        "DISGUISED", "EARLIER", 2, "Vikram Nair", RIVERSIDE, "28/08/2026", "31/08/2026", 384_500,
        "Acute calculous cholecystitis", "Laparoscopic cholecystectomy on 28/08/2026", (
            Bill("RDM-E3-BILL", RIVERSIDE, "RH-2026-11702", "31/08/2026", "Vikram Nair", "28/08/2026",
                 "31/08/2026", E3_ITEMS),
        ),
    ),
    Claim(
        "SEVERE", "SCENARIO", 1, "Farhan Shaikh", LOTUS, "10/08/2026", "11/08/2026", 740_000,
        "Acute gastroenteritis with dehydration", "Intravenous rehydration", (
            Bill("RDM-S1-INVOICE", LOTUS, "LH-2026-30418", "06/08/2026", "Farhan Shaikh", "10/08/2026",
                 "11/08/2026", (
                     Item("ICU charges", 1, 45_000),
                     Item("ICU charges", 1, 45_000),
                     Item("Ventilator support", 1, 62_000),
                     Item("Specialist consultations", 1, 38_000),
                     Item("Pharmacy and consumables", 1, 74_000),
                     Item("Laboratory investigations", 1, 46_000),
                 ), stated_total=380_000, itemized=True),
        ),
        copied=("severe-earlier-1/hospital-bill.pdf",),
    ),
    Claim(
        "CLEAN", "SCENARIO", 1, "Nisha Agarwal", GREENFIELD, "07/07/2026", "10/07/2026", 98_400,
        "Acute appendicitis", "Laparoscopic appendectomy on 07/07/2026", (
            Bill("RDM-C1-BILL", GREENFIELD, "GF-2026-05521", "10/07/2026", "Nisha Agarwal", "07/07/2026",
                 "10/07/2026", (
                     Item("Room rent (single private)", 3, 5_200),
                     Item("Surgeon fee: laparoscopic appendectomy", 1, 42_000),
                     Item("Anaesthetist fee", 1, 11_000),
                     Item("Operation theatre charges", 1, 16_500),
                     Item("Pharmacy and consumables", 1, 7_600),
                     Item("Laboratory investigations", 1, 3_900),
                     Item("Nursing charges", 3, 600),
                 )),
        ),
    ),
    Claim(
        "DISGUISED", "SCENARIO", 1, "Vikram Nair", RIVERSIDE, "18/09/2026", "20/09/2026", 392_000,
        "Acute calculous cholecystitis", "Laparoscopic cholecystectomy on 18/09/2026", (
            Bill("RDM-D1-BILL", RIVERSIDE, "RH-2026-11873", "20/09/2026", "Vikram Nair", "18/09/2026",
                 "20/09/2026", (
                     Item("Room rent (single private)", 2, 8_500),
                     *E3_ITEMS[1:5],
                     Item("Pharmacy", 1, 36_500),
                     E3_ITEMS[6],
                 )),
        ),
    ),
)  # fmt: skip


def code_line(code: str) -> Paragraph:
    return Paragraph(f"Document reference: {code}", BODY)


def stated(value: str) -> dict[str, str]:
    return {"value": value, "quote": value}


def claim_form(claim: Claim) -> tuple[list[Any], dict[str, Any]]:
    code = f"RDM-{claim.folder.upper()}-FORM"
    story = [
        Paragraph(INSURER, TITLE),
        Paragraph("Health Claim Form: Part A", HEADING),
        key_values(
            [
                ("Policy number", POLICY_NUMBER),
                ("Claimant", CLAIMANT),
                ("Patient", claim.patient),
                ("Hospital", claim.hospital),
                ("Admission date", claim.admission),
                ("Discharge date", claim.discharge),
                ("Claim type", "Reimbursement"),
                ("Total amount claimed", f"INR {amount(claim.claimed)}"),
            ]
        ),
        Spacer(1, 12),
        Paragraph("I declare that the information given here is true. Signature of claimant: <i>(signed)</i>", BODY),
        code_line(code),
    ]
    fields = {
        "policy_number": stated(POLICY_NUMBER),
        "claimant_name": stated(CLAIMANT),
        "patient_name": stated(claim.patient),
        "hospital_name": stated(claim.hospital),
        "admission_date": stated(claim.admission),
        "discharge_date": stated(claim.discharge),
        "claimed_amount": {"value": amount(claim.claimed), "quote": f"INR {amount(claim.claimed)}"},
    }
    return story, {"marker": code, "type": "CLAIM_FORM", "fields": fields, "lineItems": []}


def bill_document(bill: Bill) -> tuple[list[Any], dict[str, Any]]:
    title = "Itemized Invoice" if bill.itemized else "Final In-patient Bill"
    details = [("Invoice number" if bill.itemized else "Bill number", bill.number), ("Bill date", bill.date)]
    details += [("Patient", bill.patient), ("Admission to discharge", f"{bill.admission} to {bill.discharge}")]
    rows = [["Item", "Quantity", "Rate (INR)", "Amount (INR)"], *(item.row() for item in bill.items)]
    rows.append(["<b>Total</b>", "", "", f"<b>{amount(bill.total)}</b>"])
    story = [
        Paragraph(bill.hospital, TITLE),
        Paragraph(title, HEADING),
        key_values(details),
        Spacer(1, 8),
        table(rows, [84, 20, 34, 36]),
        Spacer(1, 8),
        code_line(bill.code),
    ]
    fields = {
        "bill_number": stated(bill.number),
        "bill_date": stated(bill.date),
        "patient_name": stated(bill.patient),
        "hospital_name": stated(bill.hospital),
        "admission_date": {"value": bill.admission, "quote": f"{bill.admission} to {bill.discharge}"},
        "discharge_date": {"value": bill.discharge, "quote": f"{bill.admission} to {bill.discharge}"},
        "total_amount": {"value": amount(bill.total), "quote": f"Total {amount(bill.total)}"},
    }
    kind = "ITEMIZED_INVOICE" if bill.itemized else "HOSPITAL_BILL"
    return story, {
        "marker": bill.code,
        "type": kind,
        "fields": fields,
        "lineItems": [item.manifest() for item in bill.items],
    }


def discharge_summary(claim: Claim) -> tuple[list[Any], dict[str, Any]]:
    code = f"RDM-{claim.folder.upper()}-DISCHARGE"
    doctor = "Dr Kavita Joshi"
    story = [
        Paragraph(claim.hospital, TITLE),
        Paragraph("Discharge Summary", HEADING),
        key_values(
            [
                ("Patient", claim.patient),
                ("Admission", claim.admission),
                ("Discharge", claim.discharge),
                ("Treating doctor", doctor),
                ("Diagnosis", claim.diagnosis),
                ("Procedure", claim.procedure),
            ]
        ),
        Paragraph("Course in hospital", HEADING),
        Paragraph("Treated and discharged in a stable condition.", BODY),
        code_line(code),
    ]
    fields = {
        "patient_name": stated(claim.patient),
        "hospital_name": stated(claim.hospital),
        "admission_date": stated(claim.admission),
        "discharge_date": stated(claim.discharge),
        "treating_doctor": stated(doctor),
        "diagnosis": stated(claim.diagnosis),
        "procedure": stated(claim.procedure),
    }
    return story, {"marker": code, "type": "DISCHARGE_SUMMARY", "fields": fields, "lineItems": []}


def iso(date: str) -> str:
    day, month, year = date.split("/")
    return f"{year}-{month}-{day}"


def main() -> None:
    if DIRECTORY.exists():
        shutil.rmtree(DIRECTORY)
    documents: list[dict[str, Any]] = []
    claims: list[dict[str, Any]] = []
    for claim in CLAIMS:
        folder = DIRECTORY / claim.folder
        files: list[dict[str, str]] = []
        story, entry = claim_form(claim)
        write_pdf(folder / "claim-form.pdf", story)
        documents.append(entry)
        files.append({"file": f"{claim.folder}/claim-form.pdf", "type": "CLAIM_FORM"})
        for copied in claim.copied:
            target = folder / Path(copied).name
            shutil.copyfile(DIRECTORY / copied, target)
            files.append({"file": f"{claim.folder}/{target.name}", "type": "HOSPITAL_BILL"})
        for bill in claim.bills:
            story, entry = bill_document(bill)
            name = "itemized-invoice.pdf" if bill.itemized else "hospital-bill.pdf"
            write_pdf(folder / name, story)
            documents.append(entry)
            files.append({"file": f"{claim.folder}/{name}", "type": entry["type"]})
        story, entry = discharge_summary(claim)
        write_pdf(folder / "discharge-summary.pdf", story)
        documents.append(entry)
        files.append({"file": f"{claim.folder}/discharge-summary.pdf", "type": "DISCHARGE_SUMMARY"})
        claims.append(
            {
                "scenario": claim.scenario,
                "role": claim.role,
                "position": claim.position,
                "claimant": CLAIMANT,
                "patient": claim.patient,
                "hospital": claim.hospital,
                "admissionDate": iso(claim.admission),
                "dischargeDate": iso(claim.discharge),
                "claimedAmount": f"{claim.claimed}.00",
                "documents": files,
            }
        )
    manifest = {
        "note": "Risk demonstration claims for Prism (M7).",
        "policy": {
            "policyNumber": POLICY_NUMBER,
            "product": "Family Health Plus",
            "policyholder": CLAIMANT,
            "effectiveFrom": "2026-01-01",
            "effectiveTo": "2026-12-31",
            "sumInsured": "500000.00",
        },
        "claims": claims,
        "documents": documents,
    }
    (DIRECTORY / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Risk demonstration documents written to {DIRECTORY}")


if __name__ == "__main__":
    main()
