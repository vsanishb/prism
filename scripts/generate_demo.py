#!/usr/bin/env python3
"""Generates the presentation data set. Output is committed.

    python -m uv run --project services/ai python scripts/generate_demo.py

Writes three things:
- demo/: three claims to upload by hand during a demonstration (blatant, clean, disguised), each with a
  README giving what to type into the claim form and what Prism should show.
- test-data/claims/demo-corpus/: the claims `prism.py demo-prepare` loads before a demonstration, so the
  system already holds claims in every state, and every risk rule has at least one claim that raises it.
  Some are the earlier claims the hand-uploaded cases are compared with.
- test-data/policies/: wordings for the two registered policies that had none, so every policy can be
  published.

The manifest records, for each claim, the rules it was built to raise. demo-prepare compares them with
what Prism raised, and prints any difference.

Everything is fictional and watermarked. These are not real insurance or medical documents.
"""

from __future__ import annotations

import json
import shutil
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from generate_risk_demo import amount
from generate_test_data import BODY, HEADING, INSURER, OUTPUT, ROOT, TITLE, key_values, table, write_pdf
from reportlab.platypus import PageBreak, Paragraph, Spacer

DEMO = ROOT / "demo"
CORPUS = OUTPUT / "claims" / "demo-corpus"
POLICIES = OUTPUT / "policies"

GOLD = "SYN-FFG-2026-0001"
GOLD_HOLDER = "Synthetic Policyholder One"
DOCTOR = "Dr Synthetic Consultant (Fictional)"

SUNRISE = "Synthetic Sunrise Hospital, Hyderabad (Fictional)"
MEADOW = "Synthetic Meadow Hospital, Bengaluru (Fictional)"
HARBOUR = "Synthetic Harbour Hospital, Chennai (Fictional)"
CEDAR = "Synthetic Cedar Hospital, Lucknow (Fictional)"
ORCHID = "Synthetic Orchid Hospital, Indore (Fictional)"
SILVERLINE = "Synthetic Silverline Hospital, Bhopal (Fictional)"
BANYAN = "Synthetic Banyan Hospital, Ahmedabad (Fictional)"
MAPLE = "Synthetic Maple Hospital, Kolkata (Fictional)"
CRESCENT = "Synthetic Crescent Hospital, Visakhapatnam (Fictional)"
GREENLEAF = "Synthetic Greenleaf Hospital, Mysuru (Fictional)"
SEAVIEW = "Synthetic Seaview Hospital, Mangaluru (Fictional)"


def printed(value: date) -> str:
    return value.strftime("%d/%m/%Y")


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


@dataclass(frozen=True)
class Bill:
    """A bill as printed. Every field is explicit, so a defect is a deliberate choice of value."""

    hospital: str
    number: str | None
    issued: date
    patient: str
    admission: date
    discharge: date
    items: tuple[Item, ...]
    stated_total: int | None = None
    itemized: bool = False

    @property
    def total(self) -> int:
        return self.stated_total if self.stated_total is not None else sum(item.total for item in self.items)


@dataclass(frozen=True)
class Stay:
    """What the claim form and discharge summary print, where it differs from the claim as entered."""

    patient: str | None = None
    hospital: str | None = None
    admission: date | None = None
    discharge: date | None = None


@dataclass(frozen=True)
class Printed:
    patient: str
    hospital: str
    admission: date
    discharge: date


@dataclass(frozen=True)
class Extra:
    """A prescription or diagnostic report, uploaded with a declared type that may differ from what it is."""

    kind: str  # PRESCRIPTION or DIAGNOSTIC_REPORT
    issued: date
    declared: str
    file: str


@dataclass(frozen=True)
class Claim:
    key: str
    folder: str
    title: str
    patient: str
    hospital: str
    admission: date
    discharge: date
    claimed: int
    diagnosis: str
    procedure: str
    bills: tuple[Bill, ...]
    expected: tuple[str, ...]
    plan: tuple[str, ...]
    stay: Stay = Stay()
    extras: tuple[Extra, ...] = ()
    discharge_summary: bool = True
    # A file from another claim's folder, uploaded unchanged as this claim's bill: the same bytes.
    copied_bill: str | None = None
    wave: int = 2
    note: str = ""

    def with_stay(self) -> Printed:
        return Printed(
            self.stay.patient or self.patient,
            self.stay.hospital or self.hospital,
            self.stay.admission or self.admission,
            self.stay.discharge or self.discharge,
        )


def items(*rows: tuple[str, int, int]) -> tuple[Item, ...]:
    return tuple(Item(*row) for row in rows)


def bill_for(claim_patient: str, hospital: str, number: str | None, admission: date, discharge: date,
             rows: tuple[Item, ...], **options: Any) -> Bill:
    return Bill(hospital, number, options.pop("issued", discharge), options.pop("patient", claim_patient), admission,
                discharge, rows, **options)


D = date

# --- Earlier claims that the hand-uploaded demonstrations are compared with -------------------------------

KIRAN_ITEMS = items(
    ("Room rent (single private)", 4, 7_200),
    ("ICU charges", 1, 21_500),
    ("Surgeon fee: open reduction of femur fracture", 1, 88_000),
    ("Anaesthetist fee", 1, 19_400),
    ("Operation theatre charges", 1, 36_800),
    ("Orthopaedic implants", 1, 64_300),
    ("Pharmacy and consumables", 1, 17_650),
    ("Physiotherapy sessions", 4, 1_350),
)
KIRAN_BILL = bill_for("Synthetic Patient Kiran", CRESCENT, "CR-2026-40712", D(2026, 8, 4), D(2026, 8, 8), KIRAN_ITEMS)

RAGHAV_SURGERY = items(
    ("Room rent (single private)", 3, 8_200),
    ("Surgeon fee: laparoscopic cholecystectomy", 1, 158_000),
    ("Anaesthetist fee", 1, 36_500),
    ("Operation theatre charges", 1, 69_800),
    ("Surgical consumables and implants", 1, 51_700),
    ("Pharmacy", 1, 22_400),
    ("Laboratory and imaging", 1, 11_350),
)

KAVYA_BILL = bill_for("Synthetic Patient Kavya", SUNRISE, "SR-2026-21877", D(2026, 5, 11), D(2026, 5, 14), items(
    ("Room rent (single private)", 3, 5_800),
    ("Surgeon fee: laparoscopic appendectomy", 1, 44_500),
    ("Anaesthetist fee", 1, 11_800),
    ("Operation theatre charges", 1, 17_250),
    ("Pharmacy and consumables", 1, 8_640),
    ("Laboratory investigations", 1, 4_310),
    ("Nursing charges", 3, 650),
))

GOPAL_ITEMS = items(
    ("Room rent (twin sharing)", 6, 4_900),
    ("Cardiac ICU charges", 2, 24_500),
    ("Interventional cardiologist fee", 1, 92_000),
    ("Cath lab charges", 1, 61_500),
    ("Drug-eluting stents", 2, 76_250),
    ("Pharmacy and consumables", 1, 33_100),
    ("Laboratory and imaging", 1, 21_150),
    ("Nursing charges", 6, 1_150),
)

IMRAN_BILL = bill_for("Synthetic Patient Imran", CEDAR, "CD-2026-08814", D(2026, 8, 3), D(2026, 8, 7), items(
    ("Room rent (twin sharing)", 4, 3_900),
    ("Consultation fees", 1, 7_800),
    ("Platelet transfusion", 2, 9_100),
    ("Pharmacy and consumables", 1, 14_260),
    ("Laboratory investigations", 1, 11_940),
    ("Nursing charges", 4, 700),
))

ROHAN_BILL = bill_for("Synthetic Patient Rohan", MEADOW, "MW-2026-15520", D(2026, 6, 2), D(2026, 6, 5), items(
    ("Room rent (single private)", 3, 6_100),
    ("Surgeon fee: open reduction and internal fixation", 1, 58_000),
    ("Anaesthetist fee", 1, 14_600),
    ("Operation theatre charges", 1, 22_900),
    ("Plate and screws", 1, 31_480),
    ("Pharmacy and consumables", 1, 6_870),
    ("Attendant charges", 3, 900),
    ("Toiletries kit", 1, 450),
))

CORPUS_CLAIMS: tuple[Claim, ...] = (
    # Wave 1: claims that later claims are compared with.
    Claim("C01", "01-clean-appendectomy", "Clean claim, approved", "Synthetic Patient Kavya", SUNRISE,
          D(2026, 5, 11), D(2026, 5, 14), KAVYA_BILL.total, "Acute appendicitis", "Laparoscopic appendectomy on 11/05/2026",
          (KAVYA_BILL,), (), ("decide:APPROVED:AGREED:Documents agree and the treatment is covered under Clause 2.1.",
                              "revise:PARTIALLY_APPROVED:-2550:AGREED:Nursing charges are part of room rent under the tariff; revised on audit."),
          wave=1),
    Claim("C02", "02-clean-fracture-partial", "Clean claim, partly approved", "Synthetic Patient Rohan", MEADOW,
          D(2026, 6, 2), D(2026, 6, 5), ROHAN_BILL.total, "Closed fracture of the right radius",
          "Open reduction and internal fixation on 02/06/2026", (ROHAN_BILL,), (),
          ("decide:PARTIALLY_APPROVED:-3150:AGREED:Attendant charges and toiletries are non-medical items under Clause 4.4.",),
          wave=1),
    Claim("C04", "04-clean-dengue-information", "Clean claim, waiting for a report", "Synthetic Patient Imran", CEDAR,
          D(2026, 8, 3), D(2026, 8, 7), IMRAN_BILL.total, "Dengue fever with thrombocytopenia", "Platelet transfusion",
          (IMRAN_BILL,), (), ("request:DIAGNOSTIC_REPORT:Please upload the platelet count reports from the admission.",),
          wave=1),
    Claim("C05", "05-large-angioplasty-senior", "Large claim, approved by a senior reviewer", "Synthetic Patient Gopal", HARBOUR,
          D(2026, 6, 15), D(2026, 6, 21), sum(item.total for item in GOPAL_ITEMS), "Coronary artery disease, double vessel",
          "Coronary angioplasty with two drug-eluting stents on 16/06/2026",
          (bill_for("Synthetic Patient Gopal", HARBOUR, "HB-2026-60231", D(2026, 6, 15), D(2026, 6, 21), GOPAL_ITEMS),), (),
          ("decide:APPROVED:AGREED:Above the reviewer limit. Documents agree and the procedure is covered.",), wave=1),
    Claim("E-BLATANT", "earlier-blatant-femur-fracture", "Earlier claim the blatant demonstration copies", "Synthetic Patient Kiran",
          CRESCENT, D(2026, 8, 4), D(2026, 8, 8), KIRAN_BILL.total, "Fracture of the left femur shaft",
          "Open reduction and internal fixation on 04/08/2026", (KIRAN_BILL,), (),
          ("decide:APPROVED:AGREED:Documents agree and the treatment is covered.",), wave=1),
    Claim("E-DISGUISED-1", "earlier-disguised-viral-fever", "Earlier claim for the disguised demonstration's patient",
          "Synthetic Patient Raghav", SEAVIEW, D(2026, 4, 20), D(2026, 4, 23), 47_160, "Viral fever", "Supportive care",
          (bill_for("Synthetic Patient Raghav", SEAVIEW, "SV-2026-03318", D(2026, 4, 20), D(2026, 4, 23), items(
              ("Room rent (single private)", 3, 6_300),
              ("Consultation fees", 1, 6_800),
              ("Pharmacy and consumables", 1, 12_460),
              ("Laboratory investigations", 1, 9_000),
          )),), (), ("decide:APPROVED:AGREED:Documents agree and the treatment is covered.",), wave=1),
    Claim("E-DISGUISED-2", "earlier-disguised-cholecystectomy", "Earlier claim the disguised demonstration resembles",
          "Synthetic Patient Raghav", SEAVIEW, D(2026, 9, 14), D(2026, 9, 17), sum(item.total for item in RAGHAV_SURGERY),
          "Acute calculous cholecystitis", "Laparoscopic cholecystectomy on 14/09/2026",
          (bill_for("Synthetic Patient Raghav", SEAVIEW, "SV-2026-04102", D(2026, 9, 14), D(2026, 9, 17), RAGHAV_SURGERY),), (),
          ("decide:APPROVED:AGREED:Above the reviewer limit. Documents agree and the procedure is covered.",), wave=1),
    # Wave 2: everything else.
    Claim("C03", "03-cataract-waiting-period", "Covered treatment inside its waiting period, denied", "Synthetic Patient Lakshmi",
          ORCHID, D(2026, 7, 20), D(2026, 7, 21), 61_940, "Senile cataract of the right eye",
          "Phacoemulsification with intraocular lens, right eye, on 20/07/2026",
          (bill_for("Synthetic Patient Lakshmi", ORCHID, "OR-2026-07745", D(2026, 7, 20), D(2026, 7, 21), items(
              ("Day care bed charges", 1, 3_400),
              ("Surgeon fee: phacoemulsification", 1, 27_500),
              ("Foldable intraocular lens", 1, 18_900),
              ("Operation theatre charges", 1, 8_260),
              ("Pharmacy and eye drops", 1, 3_880),
          )),), (), ("decide:DENIED:AGREED:Cataract has a 24-month waiting period under Clause 3.2, and cover began in April 2025.",)),
    Claim("C06", "06-patient-differs", "The bill names a different patient", "Synthetic Patient Divya", BANYAN,
          D(2026, 7, 6), D(2026, 7, 9), 0, "Acute gastroenteritis with dehydration", "Intravenous rehydration",
          (bill_for("Synthetic Patient Divya", BANYAN, "BN-2026-11406", D(2026, 7, 6), D(2026, 7, 9), items(
              ("Room rent (twin sharing)", 3, 4_100),
              ("Consultation fees", 1, 6_900),
              ("Intravenous fluids and pharmacy", 1, 18_420),
              ("Laboratory investigations", 1, 13_110),
              ("Nursing charges", 3, 1_100),
              ("Dietary charges", 3, 1_100),
          ), patient="Synthetic Patient Dhruv"),), ("CON-01",),
          ("escalate:SENIOR_REVIEW:The bill names another patient. Asking a senior reviewer before deciding.",)),
    Claim("C07", "07-hospital-and-dates-differ", "The discharge summary names another hospital and date",
          "Synthetic Patient Neel", MAPLE, D(2026, 8, 10), D(2026, 8, 13), 0, "Lower respiratory tract infection",
          "Intravenous antibiotics",
          (bill_for("Synthetic Patient Neel", MAPLE, "MP-2026-30985", D(2026, 8, 10), D(2026, 8, 13), items(
              ("Room rent (single private)", 3, 5_400),
              ("Consultation fees", 1, 8_200),
              ("Intravenous antibiotics", 1, 21_380),
              ("Chest imaging", 1, 6_900),
              ("Laboratory investigations", 1, 12_600),
              ("Nursing charges", 3, 1_200),
          )),), ("CON-02", "CON-03"), ("take",),
          stay=Stay(hospital="Synthetic Maple Nursing Home, Howrah (Fictional)", discharge=D(2026, 8, 14))),
    Claim("C08", "08-bill-before-admission", "The bill is dated before the admission, with an old prescription",
          "Synthetic Patient Pooja", SILVERLINE, D(2026, 9, 1), D(2026, 9, 4), 0, "Acute pyelonephritis",
          "Intravenous antibiotics",
          (bill_for("Synthetic Patient Pooja", SILVERLINE, "SL-2026-22719", D(2026, 9, 1), D(2026, 9, 4), items(
              ("Room rent (single private)", 3, 5_900),
              ("Consultation fees", 1, 9_300),
              ("Intravenous antibiotics", 1, 24_760),
              ("Ultrasound abdomen", 1, 4_800),
              ("Laboratory investigations", 1, 15_400),
              ("Nursing charges", 3, 1_300),
          ), issued=D(2026, 8, 27)),), ("CON-04", "CON-06"), ("wait",),
          extras=(Extra("PRESCRIPTION", D(2026, 4, 10), "PRESCRIPTION", "prescription.pdf"),)),
    Claim("C09", "09-discharge-before-admission", "The documents put the discharge before the admission",
          "Synthetic Patient Arjun", CEDAR, D(2026, 5, 20), D(2026, 5, 23), 49_870, "Acute bronchitis", "Nebulisation and oral antibiotics",
          (bill_for("Synthetic Patient Arjun", CEDAR, "CD-2026-05132", D(2026, 5, 23), D(2026, 5, 20), items(
              ("Room rent (twin sharing)", 3, 3_700),
              ("Consultation fees", 1, 6_100),
              ("Nebulisation", 6, 650),
              ("Pharmacy and consumables", 1, 11_870),
              ("Laboratory investigations", 1, 16_900),
          ), issued=D(2026, 5, 23)),), ("CON-03", "CON-05"), ("wait",),
          stay=Stay(admission=D(2026, 5, 23), discharge=D(2026, 5, 20))),
    Claim("C10", "10-items-do-not-add-up", "Bill items do not add up, and one is repeated", "Synthetic Patient Sneha",
          SUNRISE, D(2026, 9, 8), D(2026, 9, 12), 121_040, "Community-acquired pneumonia",
          "Intravenous antibiotics and oxygen therapy",
          (bill_for("Synthetic Patient Sneha", SUNRISE, "SR-2026-24410", D(2026, 9, 8), D(2026, 9, 12), items(
              ("Room rent (single private)", 4, 5_800),
              ("Consultation fees", 1, 9_900),
              ("Chest X-ray", 1, 1_850),
              ("Chest X-ray", 1, 1_850),
              ("Oxygen therapy", 4, 2_600),
              ("Intravenous antibiotics", 1, 31_740),
              ("Laboratory investigations", 1, 18_300),
          ), stated_total=121_040),), ("FIN-01", "FIN-02"), ("wait",)),
    Claim("C11", "11-claimed-above-bill", "The claim asks for more than the bill", "Synthetic Patient Manoj", CEDAR,
          D(2026, 7, 14), D(2026, 7, 18), 185_000, "Acute pancreatitis", "Conservative management",
          (bill_for("Synthetic Patient Manoj", CEDAR, "CD-2026-07220", D(2026, 7, 14), D(2026, 7, 18), items(
              ("Room rent (single private)", 4, 6_200),
              ("ICU charges", 1, 26_800),
              ("Consultation fees", 1, 12_400),
              ("Pharmacy and consumables", 1, 47_350),
              ("CT abdomen", 1, 9_650),
              ("Laboratory investigations", 1, 31_400),
          )),), ("FIN-03",),
          ("decide:PARTIALLY_APPROVED:=152400:AGREED:Approved at the billed amount; the claim asked for more than the bill.",)),
    Claim("C12", "12-above-sum-insured", "Above the sum insured, at a very high daily amount", "Synthetic Patient Farida",
          HARBOUR, D(2026, 2, 10), D(2026, 2, 12), 0, "Subarachnoid haemorrhage", "Endovascular coiling of an aneurysm",
          (bill_for("Synthetic Patient Farida", HARBOUR, "HB-2026-10477", D(2026, 2, 10), D(2026, 2, 12), items(
              ("Neuro ICU charges", 2, 38_500),
              ("Interventional neuroradiologist fee", 1, 135_000),
              ("Detachable coils", 6, 41_000),
              ("Cath lab charges", 1, 64_000),
              ("Pharmacy and consumables", 1, 23_400),
              ("Laboratory and imaging", 1, 17_600),
          )),), ("FIN-04", "FIN-06"), ("wait",), note="Policy year 2025-26: sum insured INR 5,00,000."),
    Claim("C13", "13-near-sum-insured", "Close to the whole sum insured", "Synthetic Patient Suresh", ORCHID,
          D(2026, 1, 5), D(2026, 1, 12), 0, "Necrotising pancreatitis", "Percutaneous drainage and intensive care",
          (bill_for("Synthetic Patient Suresh", ORCHID, "OR-2026-00318", D(2026, 1, 5), D(2026, 1, 12), items(
              ("ICU charges", 7, 29_000),
              ("Intensivist fee", 1, 64_000),
              ("Interventional radiology", 1, 58_500),
              ("Pharmacy and consumables", 1, 88_700),
              ("Laboratory and imaging", 1, 44_200),
              ("Nursing charges", 7, 1_800),
          )),), ("FIN-05",), ("wait",), note="Policy year 2025-26: sum insured INR 5,00,000."),
    Claim("C14", "14-copied-bill", "The bill is another claim's file, unchanged", "Synthetic Patient Tanvi", SUNRISE,
          D(2026, 9, 21), D(2026, 9, 24), KAVYA_BILL.total, "Acute appendicitis", "Laparoscopic appendectomy on 21/09/2026",
          (), ("DUP-01", "CON-01", "CON-03", "CON-04"),
          ("finding:CONFIRMED_FRAUD:The bill is the same file as another patient's claim; the hospital confirmed it issued no such bill for this patient.",
           "decide:DENIED:AGREED:The investigator confirmed the bill belongs to another claim."),
          copied_bill="01-clean-appendectomy/hospital-bill.pdf"),
    Claim("C15", "15-invoice-number-reused", "The bill number belongs to another claim at the same hospital",
          "Synthetic Patient Vivek", MEADOW, D(2026, 9, 2), D(2026, 9, 5), 64_890, "Acute cholecystitis", "Conservative management",
          (bill_for("Synthetic Patient Vivek", MEADOW, "MW-2026-15520", D(2026, 9, 2), D(2026, 9, 5), items(
              ("Room rent (twin sharing)", 3, 4_400),
              ("Consultation fees", 1, 7_300),
              ("Ultrasound abdomen", 1, 5_150),
              ("Pharmacy and consumables", 1, 19_640),
              ("Laboratory investigations", 1, 19_600),
          )),), ("DUP-02",), ("wait",)),
    Claim("C16", "16-near-identical-bill", "A lightly altered copy of another patient's bill", "Synthetic Patient Ramesh",
          HARBOUR, D(2026, 10, 5), D(2026, 10, 11), 0, "Coronary artery disease, double vessel",
          "Coronary angioplasty with two drug-eluting stents on 06/10/2026",
          (bill_for("Synthetic Patient Ramesh", HARBOUR, "HB-2026-71940", D(2026, 10, 5), D(2026, 10, 11), (
              *GOPAL_ITEMS[:5], Item("Pharmacy and consumables", 1, 35_900), GOPAL_ITEMS[6], GOPAL_ITEMS[7],
          )),), ("DUP-03",),
          ("finding:NO_CONCERN:The hospital confirmed both bills follow its fixed angioplasty package, which explains the matching lines.",
           "wait")),
    Claim("C17", "17-overlapping-stay", "The same patient was in another hospital at the same time", "Synthetic Patient Kavya",
          MAPLE, D(2026, 5, 13), D(2026, 5, 16), 57_310, "Post-operative wound infection", "Wound debridement",
          (bill_for("Synthetic Patient Kavya", MAPLE, "MP-2026-12053", D(2026, 5, 13), D(2026, 5, 16), items(
              ("Room rent (twin sharing)", 3, 4_600),
              ("Surgeon fee: wound debridement", 1, 21_000),
              ("Dressings and consumables", 1, 9_880),
              ("Intravenous antibiotics", 1, 12_630),
          )),), ("DUP-04",), ("wait",)),
    Claim("C18", "18-similar-claim-soon-after", "A similar claim at the same hospital weeks later", "Synthetic Patient Imran", CEDAR,
          D(2026, 8, 25), D(2026, 8, 29), 76_420, "Dengue fever", "Supportive care",
          (bill_for("Synthetic Patient Imran", CEDAR, "CD-2026-09203", D(2026, 8, 25), D(2026, 8, 29), items(
              ("Room rent (twin sharing)", 4, 4_050),
              ("Consultation fees", 1, 8_100),
              ("Intravenous fluids", 1, 9_460),
              ("Pharmacy and consumables", 1, 16_900),
              ("Laboratory investigations", 1, 23_760),
              ("Nursing charges", 4, 500),
          )),), ("DUP-05",),
          ("decide:APPROVED:PARTLY_AGREED:A second admission is plausible after dengue; reports confirm a relapse.",)),
    Claim("C19", "19-document-type-differs", "A report uploaded as a prescription", "Synthetic Patient Meher", BANYAN,
          D(2026, 10, 2), D(2026, 10, 4), 44_620, "Acute gastritis", "Endoscopy and medical management",
          (bill_for("Synthetic Patient Meher", BANYAN, "BN-2026-13392", D(2026, 10, 2), D(2026, 10, 4), items(
              ("Room rent (twin sharing)", 2, 4_250),
              ("Consultation fees", 1, 5_600),
              ("Upper GI endoscopy", 1, 14_900),
              ("Pharmacy and consumables", 1, 7_820),
              ("Laboratory investigations", 1, 7_800),
          )),), ("DOC-01",),
          ("decide:APPROVED:AGREED:The report was uploaded under the wrong type; its content supports the claim.",),
          extras=(Extra("DIAGNOSTIC_REPORT", D(2026, 10, 2), "PRESCRIPTION", "endoscopy-report.pdf"),)),
    Claim("C20", "20-bill-without-details", "A bill with no number and no items", "Synthetic Patient Yusuf", SILVERLINE,
          D(2026, 10, 12), D(2026, 10, 15), 68_500, "Renal colic", "Medical management",
          (bill_for("Synthetic Patient Yusuf", SILVERLINE, None, D(2026, 10, 12), D(2026, 10, 15), (), stated_total=68_500),),
          ("DOC-02", "DOC-03"),
          ("request:ITEMIZED_INVOICE:The bill has no number or items. Please upload the itemized invoice.",)),
    Claim("C21", "21-missing-discharge-summary", "The discharge summary was never supplied", "Synthetic Patient Bhavna", MAPLE,
          D(2026, 10, 19), D(2026, 10, 22), 53_960, "", "",
          (bill_for("Synthetic Patient Bhavna", MAPLE, "MP-2026-33108", D(2026, 10, 19), D(2026, 10, 22), items(
              ("Room rent (twin sharing)", 3, 4_600),
              ("Consultation fees", 1, 6_200),
              ("Pharmacy and consumables", 1, 18_460),
              ("Laboratory investigations", 1, 15_500),
          )),), (), ("wait",), discharge_summary=False,
          note="Waits for the discharge summary. It is screened, and MIS-01 raised, only if the 14-day wait expires."),
    Claim("C22", "22-diagnosis-not-stated", "The discharge summary gives no diagnosis", "Synthetic Patient Harini", ORCHID,
          D(2026, 10, 26), D(2026, 10, 28), 0, "", "Observation and intravenous fluids",
          (bill_for("Synthetic Patient Harini", ORCHID, "OR-2026-09961", D(2026, 10, 26), D(2026, 10, 28), items(
              ("Room rent (twin sharing)", 2, 4_350),
              ("Consultation fees", 1, 5_900),
              ("Intravenous fluids and pharmacy", 1, 11_740),
              ("Laboratory investigations", 1, 13_260),
          )),), ("MIS-02",), ("wait",)),
)

# --- The three hand-uploaded demonstrations ------------------------------------------------------------------

BLATANT = Claim(
    "DEMO-BLATANT", "1-blatant-fraud", "Blatant: plainly inconsistent, with a bill copied from another claim",
    "Synthetic Patient Aditya", CRESCENT, D(2026, 10, 12), D(2026, 10, 13), 1_060_000,
    "Fracture of the left femur shaft", "Open reduction and internal fixation on 12/10/2026",
    (bill_for("Synthetic Patient Aditya", CRESCENT, "CR-2026-40712", D(2026, 10, 12), D(2026, 10, 13), items(
        ("ICU charges", 1, 86_000),
        ("ICU charges", 1, 86_000),
        ("Surgeon fee: open reduction of femur fracture", 1, 188_000),
        ("Orthopaedic implants", 1, 164_300),
        ("Operation theatre charges", 1, 96_800),
        ("Pharmacy and consumables", 1, 77_650),
    ), stated_total=742_000, itemized=True, issued=D(2026, 10, 7)),),
    ("DUP-01", "DUP-02", "CON-01", "CON-03", "CON-04", "FIN-01", "FIN-02", "FIN-03", "FIN-04", "FIN-06"),
    (), copied_bill="earlier-blatant-femur-fracture/hospital-bill.pdf",
)

CLEAN = Claim(
    "DEMO-CLEAN", "2-clean-claim", "Clean: an ordinary, consistent claim", "Synthetic Patient Ananya", GREENLEAF,
    D(2026, 10, 5), D(2026, 10, 8), 96_870, "Acute appendicitis", "Laparoscopic appendectomy on 05/10/2026",
    (bill_for("Synthetic Patient Ananya", GREENLEAF, "GL-2026-06684", D(2026, 10, 5), D(2026, 10, 8), items(
        ("Room rent (single private)", 3, 5_350),
        ("Surgeon fee: laparoscopic appendectomy", 1, 41_200),
        ("Anaesthetist fee", 1, 10_900),
        ("Operation theatre charges", 1, 16_850),
        ("Pharmacy and consumables", 1, 7_420),
        ("Laboratory investigations", 1, 4_100),
        ("Nursing charges", 3, 550),
    )),), (), (),
)

DISGUISED = Claim(
    "DEMO-DISGUISED", "3-disguised-fraud", "Disguised: consistent documents, but the bill is a light copy of an earlier one",
    "Synthetic Patient Raghav", SEAVIEW, D(2026, 10, 5), D(2026, 10, 7), 0, "Acute calculous cholecystitis",
    "Laparoscopic cholecystectomy on 05/10/2026",
    (bill_for("Synthetic Patient Raghav", SEAVIEW, "SV-2026-04377", D(2026, 10, 5), D(2026, 10, 7), (
        Item("Room rent (single private)", 2, 8_200),
        *RAGHAV_SURGERY[1:5],
        Item("Pharmacy", 1, 38_900),
        RAGHAV_SURGERY[6],
    )),), ("DUP-03", "DUP-05"), (),
)

# --- Documents -----------------------------------------------------------------------------------------------


def code_line(code: str) -> Paragraph:
    return Paragraph(f"Synthetic document code: {code}", BODY)


def claim_form(claim: Claim, code: str) -> list[Any]:
    stay = claim.with_stay()
    return [
        Paragraph(INSURER, TITLE),
        Paragraph("Health Claim Form: Part A", HEADING),
        key_values([
            ("Policy number", GOLD),
            ("Claimant", GOLD_HOLDER),
            ("Patient", stay.patient),
            ("Relationship to claimant", "Family member"),
            ("Hospital", claim.hospital),
            ("Admission date", printed(stay.admission)),
            ("Discharge date", printed(stay.discharge)),
            ("Claim type", "Reimbursement"),
            ("Total amount claimed", f"INR {amount(claim.claimed)}"),
            ("Bank details", "Withheld in synthetic data"),
        ]),
        Spacer(1, 12),
        Paragraph("I declare that the information given here is true. Signed: <i>Synthetic signature</i>", BODY),
        code_line(code),
    ]


def bill_document(bill: Bill, code: str) -> list[Any]:
    title = "Itemized Invoice" if bill.itemized else "Final In-patient Bill"
    details: list[tuple[str, str]] = []
    if bill.number:
        details.append(("Invoice number" if bill.itemized else "Bill number", bill.number))
    details += [
        ("Bill date", printed(bill.issued)),
        ("Patient", bill.patient),
        ("Admission to discharge", f"{printed(bill.admission)} to {printed(bill.discharge)}"),
    ]
    story: list[Any] = [Paragraph(bill.hospital, TITLE), Paragraph(title, HEADING), key_values(details), Spacer(1, 8)]
    if bill.items:
        rows = [["Item", "Quantity", "Rate (INR)", "Amount (INR)"], *(item.row() for item in bill.items)]
        rows.append(["<b>Total</b>", "", "", f"<b>{amount(bill.total)}</b>"])
        story.append(table(rows, [84, 20, 34, 36]))
    else:
        story.append(key_values([("Hospital charges, all inclusive", f"INR {amount(bill.total)}"),
                                 ("Total payable", f"INR {amount(bill.total)}")]))
    story += [Spacer(1, 8), code_line(code)]
    return story


def discharge_summary(claim: Claim, code: str) -> list[Any]:
    stay = claim.with_stay()
    return [
        Paragraph(stay.hospital, TITLE),
        Paragraph("Discharge Summary", HEADING),
        key_values([(label, value) for label, value in [
            ("Patient", stay.patient),
            ("Admission", printed(stay.admission)),
            ("Discharge", printed(stay.discharge)),
            ("Treating doctor", DOCTOR),
            ("Diagnosis", claim.diagnosis),
            ("Procedure", claim.procedure),
        ] if value]),
        Paragraph("Course in hospital", HEADING),
        Paragraph("Fictional narrative: treated as planned and discharged in a stable condition.", BODY),
        Paragraph("Advice on discharge", HEADING),
        Paragraph("Fictional advice: review in the outpatient clinic after seven days.", BODY),
        code_line(code),
    ]


def extra_document(claim: Claim, extra: Extra, code: str) -> list[Any]:
    if extra.kind == "PRESCRIPTION":
        body = [
            key_values([("Patient", claim.patient), ("Date", printed(extra.issued)), ("Doctor", DOCTOR)]),
            Spacer(1, 6),
            table([["Medicine", "Dose", "Duration"],
                   ["Tab. Synthetic Nitrofurantoin 100 mg", "Twice daily", "5 days"],
                   ["Tab. Synthetic Paracetamol 650 mg", "When needed", "3 days"]], [80, 50, 44]),
        ]
        heading = "Outpatient Prescription"
    else:
        body = [
            key_values([("Patient", claim.patient), ("Report date", printed(extra.issued)), ("Reported by", DOCTOR),
                        ("Investigation", "Upper GI endoscopy")]),
            Paragraph("Findings", HEADING),
            Paragraph("Fictional findings: diffuse antral erythema. No ulcer. No bleeding.", BODY),
            Paragraph("Impression", HEADING),
            Paragraph("Fictional impression: acute gastritis.", BODY),
        ]
        heading = "Diagnostic Report"
    return [Paragraph(claim.hospital, TITLE), Paragraph(heading, HEADING), *body, Spacer(1, 8), code_line(code)]


def write_claim(claim: Claim, folder: Path, source_root: Path) -> list[dict[str, str]]:
    """Writes the claim's documents, and returns them in upload order with their declared types."""
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    prefix = f"PRISM-{claim.key}"
    files: list[dict[str, str]] = []
    write_pdf(folder / "claim-form.pdf", claim_form(claim, f"{prefix}-FORM"))
    files.append({"file": "claim-form.pdf", "type": "CLAIM_FORM"})
    if claim.copied_bill:
        shutil.copyfile(source_root / claim.copied_bill, folder / "hospital-bill.pdf")
        files.append({"file": "hospital-bill.pdf", "type": "HOSPITAL_BILL"})
    for index, bill in enumerate(claim.bills):
        name = "itemized-invoice.pdf" if bill.itemized else "hospital-bill.pdf"
        write_pdf(folder / name, bill_document(bill, f"{prefix}-BILL{index + 1}"))
        files.append({"file": name, "type": "ITEMIZED_INVOICE" if bill.itemized else "HOSPITAL_BILL"})
    if claim.discharge_summary:
        write_pdf(folder / "discharge-summary.pdf", discharge_summary(claim, f"{prefix}-DISCHARGE"))
        files.append({"file": "discharge-summary.pdf", "type": "DISCHARGE_SUMMARY"})
    for extra in claim.extras:
        write_pdf(folder / extra.file, extra_document(claim, extra, f"{prefix}-{extra.kind}"))
        files.append({"file": extra.file, "type": extra.declared})
    return files


def resolved(claim: Claim) -> Claim:
    """A claimed amount of 0 means exactly what the bills total. Typing totals by hand is how the first
    version of this set came to claim more than its bills, which FIN-03 duly reported."""
    if claim.claimed:
        return claim
    total = sum(bill.total for bill in claim.bills)
    return Claim(**{**claim.__dict__, "claimed": total})


def entry(claim: Claim, files: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "key": claim.key,
        "folder": claim.folder,
        "title": claim.title,
        "policyNumber": GOLD,
        "claimant": GOLD_HOLDER,
        "patient": claim.patient,
        "hospital": claim.hospital,
        "admissionDate": claim.admission.isoformat(),
        "dischargeDate": claim.discharge.isoformat(),
        "claimedAmount": f"{claim.claimed}.00",
        "documents": files,
        "expectedRules": list(claim.expected),
        "plan": list(claim.plan),
        "wave": claim.wave,
        "note": claim.note,
    }


# --- Demonstration READMEs -----------------------------------------------------------------------------------

RULE_TITLES = {
    "CON-01": "The patient differs across documents",
    "CON-02": "The hospital differs across documents",
    "CON-03": "The dates of stay differ across documents",
    "CON-04": "A bill is dated before the admission",
    "CON-05": "The discharge is before the admission",
    "CON-06": "A document is dated far outside the stay",
    "FIN-01": "Bill items do not add up to the total",
    "FIN-02": "An item is repeated on a bill",
    "FIN-03": "The claim is above the bills",
    "FIN-04": "The claim is above the sum insured",
    "FIN-05": "The claim is close to the sum insured",
    "FIN-06": "A very high amount per day of stay",
    "DUP-01": "The same file is in another claim",
    "DUP-02": "An invoice number is reused at the same hospital",
    "DUP-03": "A near-identical bill is in another claim",
    "DUP-04": "An overlapping stay for the same patient",
    "DUP-05": "A similar claim shortly before or after",
    "DOC-01": "The document read differs from its declared type",
    "DOC-02": "A bill is missing its number, date, or total",
    "DOC-03": "A bill has no line items",
    "MIS-01": "A required document is missing",
    "MIS-02": "Critical facts are not established",
}

DEMO_STORY = {
    "DEMO-BLATANT": (
        "HIGH",
        "Investigation queue",
        [
            "`hospital-bill.pdf` is byte-for-byte the bill from claim *Synthetic Patient Kiran* (loaded by demo-prepare). "
            "It names Kiran, not Aditya, and different dates.",
            "`itemized-invoice.pdf` reuses Kiran's invoice number at the same hospital, is dated five days before the "
            "admission, repeats the ICU line, and its items do not add up to its total.",
            "INR 10,60,000 is claimed for one night: more than both bills and more than the INR 10,00,000 sum insured.",
        ],
        "Open the claim's **Risk** tab: every indicator links to its page. Then sign in as the investigator to show the "
        "claim waiting in the investigation queue. Point out that Prism says *for review*, never *fraud*.",
    ),
    "DEMO-CLEAN": (
        "LOW",
        "Review queue",
        [
            "Every document agrees with the others and with the form.",
            "The bill adds up, and the claim asks for exactly the bill.",
            "No other claim shares a file, an invoice number, or a bill with it.",
        ],
        "Show the policy analysis (appendicitis is covered under Clause 2.1), the empty indicator list, and the "
        "claim waiting in the ordinary review queue. A LOW band changes nothing but how closely it is reviewed.",
    ),
    "DEMO-DISGUISED": (
        "HIGH (MEDIUM from the rules alone)",
        "Investigation queue",
        [
            "Every document is consistent. Nothing on the page looks wrong.",
            "The bill is a light edit of the same patient's bill from 18 days earlier (claim for a cholecystectomy, "
            "loaded by demo-prepare): five of seven lines match exactly.",
            "Same patient, same hospital, similar amount, inside 30 days: a similar claim.",
            "The statistical detectors find the claim unusual (a large surgical claim, a third claim in six months).",
        ],
        "Open the **Risk** tab and show the score breakdown: the rules alone give MEDIUM, and the two anomaly detectors "
        "add the points that make it HIGH. This is the case the ML layer exists for.",
    ),
}


def demo_readme(claim: Claim, files: list[dict[str, str]]) -> str:
    band, queue, why, show = DEMO_STORY[claim.key]
    rows = "\n".join(f"| {name} | {value} |" for name, value in [
        ("Claim type", "Reimbursement"),
        ("Policy", f"{GOLD} (Family Floater Gold)"),
        ("Claimant", GOLD_HOLDER),
        ("Patient", claim.patient),
        ("Hospital", claim.hospital),
        ("Admission date", printed(claim.admission)),
        ("Discharge date", printed(claim.discharge)),
        ("Claimed amount", f"INR {amount(claim.claimed)}"),
    ])
    uploads = "\n".join(f"| `{item['file']}` | {item['type'].replace('_', ' ').title()} |" for item in files)
    rules = "\n".join(f"- `{rule}` {RULE_TITLES[rule]}" for rule in claim.expected) or "- None"
    reasons = "\n".join(f"- {reason}" for reason in why)
    return f"""# {claim.title.split(':')[0]} claim

{claim.title.split(': ', 1)[1].capitalize()}.

**Expected:** risk band **{band}**, routed to the **{queue}**.

## 1. Create the claim

Sign in as `claims.processor@synthetic.prism.test`, open **Claims**, and choose **New claim**. Enter exactly:

| Field | Value |
|---|---|
{rows}

## 2. Upload the documents

| File | Document type |
|---|---|
{uploads}

Then choose **Submit for processing**. Processing takes one to three minutes with the real model.

## 3. What Prism should find

{reasons}

Rules expected to fire:

{rules}

## 4. What to show

{show}

The person who created a claim can never review it. Sign in as `reviewer`, `senior.reviewer`, or `investigator` to work it.

All names, hospitals, and amounts are fictional.
"""


DEMO_INDEX = """# Demonstration claims

Three claims to upload by hand during a demonstration. Each folder holds the documents and a README with the
exact values to type and what Prism should show.

| Folder | Case | Expected band |
|---|---|---|
| [1-blatant-fraud](1-blatant-fraud/README.md) | Plainly inconsistent, with a bill copied from another claim | HIGH |
| [2-clean-claim](2-clean-claim/README.md) | An ordinary, consistent claim | LOW |
| [3-disguised-fraud](3-disguised-fraud/README.md) | Consistent documents, but a light copy of an earlier bill | HIGH |

## Before you start

Run this once, with the stack running (see the project README):

```bash
python scripts/prism.py demo-prepare
```

It publishes every policy and loads 25 claims in every state: waiting, taken, decided, revised,
under investigation, waiting for documents. Several are the earlier claims these three are compared with,
so **the demonstrations only work after demo-prepare**. It prints a table of what each claim raised.

## Upload each case once

The duplicate rules compare every submitted claim. If you upload a case twice, the second copy is compared
with the first and is flagged as a duplicate, even the clean one. After a rehearsal, either withdraw the
rehearsal claims, or start fresh:

```bash
python scripts/prism.py db-reset
python scripts/prism.py demo-prepare
```

Every person, hospital, policy, and amount here is fictional. The documents are watermarked.
"""

# --- Policy wordings for the policies registered without one -------------------------------------------------


def simple_wording(product: str, number: str, holder: str, period: str, sum_insured: str, room: str,
                   copay: str) -> list[Any]:
    return [
        Paragraph(INSURER, TITLE),
        Paragraph(f"{product}: Policy Wording and Schedule", HEADING),
        Paragraph(f"Version 1. Policy period {period}.", BODY),
        Spacer(1, 6),
        Paragraph("Schedule", HEADING),
        key_values([
            ("Policy number", number),
            ("Policyholder", holder),
            ("Sum Insured", f"INR {sum_insured} per policy year"),
            ("Co-payment", copay),
            ("Deductible", "Nil"),
        ]),
        Paragraph("Section 1: Definitions", HEADING),
        Paragraph("<b>1.1 Hospitalization</b> means admission to a Hospital for at least 24 consecutive in-patient "
                  "care hours, except for Day Care Treatment as defined in Clause 1.2.", BODY),
        Paragraph("<b>1.2 Day Care Treatment</b> means a procedure completed in under 24 hours of admission.", BODY),
        Paragraph("<b>1.3 Pre-existing Disease</b> means any condition diagnosed within 48 months before cover began.", BODY),
        Paragraph("Section 2: Benefits and Limits", HEADING),
        table([
            ["Clause", "Benefit", "Limit", "Conditions"],
            ["2.1", "In-patient Hospitalization", "Up to Sum Insured", "Subject to Clause 1.1"],
            ["2.2", "Room rent", room, "Proportionate deduction applies if exceeded"],
            ["2.3", "Intensive care unit", "2% of Sum Insured per day", "Medically necessary admission only"],
            ["2.4", "Pre-hospitalization", "30 days before admission", "Related to the admitted condition"],
            ["2.5", "Post-hospitalization", "60 days after discharge", "Related to the admitted condition"],
            ["2.6", "Ambulance", "INR 2,000 per Hospitalization", "Registered ambulance services only"],
        ], [14, 52, 48, 60]),
        PageBreak(),
        Paragraph("Section 3: Waiting Periods", HEADING),
        Paragraph("<b>3.1</b> No claim is payable in the first 30 days of the policy, except for accidents.", BODY),
        Paragraph("<b>3.2</b> A 24-month waiting period applies to cataract, hernia, and kidney stones.", BODY),
        Paragraph("<b>3.3</b> Pre-existing Diseases, as defined in Clause 1.3, are covered after 48 months.", BODY),
        Paragraph("Section 4: Exclusions", HEADING),
        Paragraph("<b>4.1</b> Cosmetic or aesthetic treatment, except reconstruction following an accident.", BODY),
        Paragraph("<b>4.2</b> Non-medical items such as toiletries and attendant charges.", BODY),
        Paragraph("Section 5: Claim Procedure", HEADING),
        Paragraph("<b>5.1</b> Notify the Insurer within 48 hours of an emergency admission.", BODY),
        Paragraph("<b>5.2</b> Submit documents within 30 days of discharge.", BODY),
    ]


def main() -> None:
    corpus_entries: list[dict[str, Any]] = []
    for claim in map(resolved, CORPUS_CLAIMS):
        files = write_claim(claim, CORPUS / claim.folder, CORPUS)
        corpus_entries.append(entry(claim, [{**item, "file": f"{claim.folder}/{item['file']}"} for item in files]))

    demo_entries: list[dict[str, Any]] = []
    for claim in map(resolved, (BLATANT, CLEAN, DISGUISED)):
        files = write_claim(claim, DEMO / claim.folder, CORPUS)
        (DEMO / claim.folder / "README.md").write_text(demo_readme(claim, files), encoding="utf-8")
        demo_entries.append(entry(claim, [{**item, "file": f"{claim.folder}/{item['file']}"} for item in files]))
    (DEMO / "README.md").write_text(DEMO_INDEX, encoding="utf-8")

    manifest = {
        "note": "Synthetic presentation data for Prism. Fictional people, hospitals, and amounts.",
        "claims": corpus_entries,
        "demonstrations": demo_entries,
    }
    (CORPUS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    write_pdf(POLICIES / "individual-silver.pdf", simple_wording(
        "Individual Silver", "SYN-IND-2026-0002", "Synthetic Policyholder Two", "1 January 2026 to 31 December 2026",
        "3,00,000", "1% of Sum Insured per day", "Nil"))
    write_pdf(POLICIES / "family-health-plus.pdf", simple_wording(
        "Family Health Plus", "SYN-RSK-2026-0003", "Synthetic Policyholder Four", "1 January 2026 to 31 December 2026",
        "5,00,000", "Single private room", "10% of each admissible claim where the Insured Person is aged 61 or above"))
    print(f"Wrote {len(corpus_entries)} corpus claims, 3 demonstrations, and 2 policy wordings.")


if __name__ == "__main__":
    main()
