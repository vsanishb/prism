#!/usr/bin/env python3
"""Generates Prism's synthetic test documents into test-data/. Output is committed.

    uv run --project services/ai python scripts/generate_test_data.py

Every person, organization, and policy here is fictional, and every page is watermarked.
These files must never be presented as real insurance or medical documents.

--oversized also writes a large file into .data/, which is too big to commit.
"""

from __future__ import annotations

import argparse
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib import colors
from reportlab.lib.enums import TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfgen.canvas import Canvas
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "test-data"

INSURER = "Prism Test Assurance Ltd (Fictional)"
HOSPITAL = "Synthetic Care Hospital, Pune (Fictional)"
POLICY_NUMBER = "SYN-FFG-2026-0001"
WATERMARK = "SYNTHETIC TEST DOCUMENT"
FOOTER = "Fictional data for software testing. Not a real insurance or medical document."

STYLES = getSampleStyleSheet()
BODY = ParagraphStyle("Body", parent=STYLES["BodyText"], fontSize=9.5, leading=13)
HEADING = ParagraphStyle("Heading", parent=STYLES["Heading2"], fontSize=12, spaceBefore=10, spaceAfter=4)
TITLE = ParagraphStyle("Title", parent=STYLES["Title"], fontSize=16, spaceAfter=2)
RIGHT = ParagraphStyle("Right", parent=BODY, alignment=TA_RIGHT)

GRID = TableStyle(
    [
        ("GRID", (0, 0), (-1, -1), 0.4, colors.grey),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eeeeee")),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]
)


def stamp(canvas: Canvas, _document: Any) -> None:
    """Marks every page, so that a page taken alone still cannot be mistaken for a real one."""
    canvas.saveState()
    canvas.setFont("Helvetica-Bold", 40)
    canvas.setFillColor(colors.Color(0.75, 0.1, 0.1, alpha=0.11))
    canvas.translate(A4[0] / 2, A4[1] / 2)
    canvas.rotate(45)
    canvas.drawCentredString(0, 0, WATERMARK)
    canvas.restoreState()
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(colors.grey)
    canvas.drawCentredString(A4[0] / 2, 10 * mm, FOOTER)


def write_pdf(path: Path, story: list[Any], **options: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = SimpleDocTemplate(
        str(path),
        pagesize=A4,
        title=f"Synthetic test document: {path.stem}",
        author="Prism test data generator",
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=16 * mm,
        bottomMargin=18 * mm,
        invariant=1,
        **options,
    )
    document.build(story, onFirstPage=stamp, onLaterPages=stamp)


def table(rows: list[list[str]], widths: list[float] | None = None) -> Table:
    cells = [[Paragraph(cell, BODY) if index else cell for cell in row] for index, row in enumerate(rows)]
    return Table(cells, colWidths=[width * mm for width in widths] if widths else None, style=GRID, repeatRows=1)


def key_values(rows: list[tuple[str, str]]) -> Table:
    cells = [[Paragraph(f"<b>{key}</b>", BODY), Paragraph(value, BODY)] for key, value in rows]
    style = TableStyle([("GRID", (0, 0), (-1, -1), 0.3, colors.lightgrey), ("VALIGN", (0, 0), (-1, -1), "TOP")])
    return Table(cells, colWidths=[55 * mm, 119 * mm], style=style)


def policy_wording(version: int) -> list[Any]:
    """A policy with the structures M4 must preserve: schedule, limits, exclusions, cross-references."""
    year = 2024 + version
    sum_insured = "5,00,000" if version == 1 else "10,00,000"
    room_limit = "1% of Sum Insured per day" if version == 1 else "Single private room"
    story: list[Any] = [
        Paragraph(INSURER, TITLE),
        Paragraph("Family Floater Gold: Policy Wording and Schedule", HEADING),
        Paragraph(f"Version {version}. Policy period 1 April {year} to 31 March {year + 1}.", BODY),
        Spacer(1, 6),
        Paragraph("Schedule", HEADING),
        key_values(
            [
                ("Policy number", POLICY_NUMBER),
                ("Policyholder", "Synthetic Policyholder One"),
                ("Sum Insured", f"INR {sum_insured} per policy year"),
                ("Co-payment", "10% of each admissible claim where the Insured Person is aged 61 or above"),
                ("Deductible", "Nil"),
            ]
        ),
        Paragraph("Section 1: Definitions", HEADING),
        Paragraph(
            "<b>1.1 Hospitalization</b> means admission to a Hospital for at least 24 consecutive in-patient "
            "care hours, except for Day Care Treatment as defined in Clause 1.2.",
            BODY,
        ),
        Paragraph(
            "<b>1.2 Day Care Treatment</b> means a procedure completed in under 24 hours of admission because "
            "of technological advancement.",
            BODY,
        ),
        Paragraph(
            "<b>1.3 Pre-existing Disease</b> means any condition diagnosed within 48 months before the first "
            "policy issued by the Insurer.",
            BODY,
        ),
        Paragraph("Section 2: Benefits and Limits", HEADING),
        table(
            [
                ["Clause", "Benefit", "Limit", "Conditions"],
                ["2.1", "In-patient Hospitalization", "Up to Sum Insured", "Subject to Clause 1.1"],
                ["2.2", "Room rent", room_limit, "Proportionate deduction applies if exceeded"],
                ["2.3", "Intensive care unit", "2% of Sum Insured per day", "Medically necessary admission only"],
                ["2.4", "Pre-hospitalization", "30 days before admission", "Related to the admitted condition"],
                ["2.5", "Post-hospitalization", "60 days after discharge", "Related to the admitted condition"],
                ["2.6", "Cataract surgery", "INR 40,000 per eye", "See waiting period in Clause 3.2"],
                ["2.7", "Ambulance", "INR 2,500 per Hospitalization", "Registered ambulance services only"],
            ],
            [14, 52, 48, 60],
        ),
        PageBreak(),
        Paragraph("Section 3: Waiting Periods", HEADING),
        Paragraph("<b>3.1</b> No claim is payable in the first 30 days of the policy, except for accidents.", BODY),
        Paragraph(
            "<b>3.2</b> A 24-month waiting period applies to cataract, hernia, joint replacement, and "
            "kidney stones, unless the condition arises from an accident.",
            BODY,
        ),
        Paragraph("<b>3.3</b> Pre-existing Diseases, as defined in Clause 1.3, are covered after 36 months.", BODY),
        Paragraph("Section 4: Exclusions", HEADING),
        Paragraph("<b>4.1</b> Cosmetic or aesthetic treatment, except reconstruction following an accident.", BODY),
        Paragraph("<b>4.2</b> Treatment of obesity, unless the conditions of Clause 4.3 are all met.", BODY),
        Paragraph("<b>4.3</b> Bariatric surgery is covered only if BMI is 40 or above, and advised by a doctor.", BODY),
        Paragraph("<b>4.4</b> Non-medical items listed in Annexure A, such as toiletries and attendant charges.", BODY),
        Paragraph("Section 5: Claim Procedure", HEADING),
        Paragraph("<b>5.1</b> Notify the Insurer within 48 hours of an emergency admission.", BODY),
        Paragraph("<b>5.2</b> Submit documents within 30 days of discharge.", BODY),
        table(
            [
                ["Required document", "Reimbursement", "Cashless"],
                ["Signed claim form", "Required", "Required"],
                ["Itemized hospital bill", "Required", "Required"],
                ["Discharge summary", "Required", "Required"],
                ["Prescriptions and reports", "Required", "On request"],
            ],
            [84, 45, 45],
        ),
    ]
    return story


def claim_form(patient: str, claimed: str) -> list[Any]:
    return [
        Paragraph(INSURER, TITLE),
        Paragraph("Health Claim Form: Part A", HEADING),
        key_values(
            [
                ("Policy number", POLICY_NUMBER),
                ("Claimant", "Synthetic Policyholder One"),
                ("Patient", patient),
                ("Relationship to claimant", "Spouse"),
                ("Hospital", HOSPITAL),
                ("Admission date", "14/03/2026"),
                ("Discharge date", "17/03/2026"),
                ("Claim type", "Reimbursement"),
                ("Total amount claimed", f"INR {claimed}"),
                ("Bank details", "Withheld in synthetic data"),
            ]
        ),
        Spacer(1, 12),
        Paragraph("I declare that the information given here is true. Signed: <i>Synthetic signature</i>", BODY),
    ]


def hospital_bill(patient: str) -> list[Any]:
    return [
        Paragraph(HOSPITAL, TITLE),
        Paragraph("Final In-patient Bill", HEADING),
        key_values(
            [
                ("Bill number", "SCH-IP-2026-004417"),
                ("Bill date", "17/03/2026"),
                ("Patient", patient),
                ("Admission to discharge", "14/03/2026 to 17/03/2026"),
            ]
        ),
        Spacer(1, 8),
        table(
            [
                ["Item", "Quantity", "Rate (INR)", "Amount (INR)"],
                ["Room rent (single private)", "3", "6,500.00", "19,500.00"],
                ["Surgeon fee: laparoscopic appendectomy", "1", "42,000.00", "42,000.00"],
                ["Anaesthetist fee", "1", "12,000.00", "12,000.00"],
                ["Operation theatre charges", "1", "18,500.00", "18,500.00"],
                ["Pharmacy and consumables", "1", "9,250.00", "9,250.00"],
                ["Laboratory investigations", "1", "4,750.00", "4,750.00"],
                ["<b>Total</b>", "", "", "<b>1,06,000.00</b>"],
            ],
            [84, 20, 34, 36],
        ),
    ]


def discharge_summary(patient: str) -> list[Any]:
    return [
        Paragraph(HOSPITAL, TITLE),
        Paragraph("Discharge Summary", HEADING),
        key_values(
            [
                ("Patient", patient),
                ("Admission", "14/03/2026"),
                ("Discharge", "17/03/2026"),
                ("Treating doctor", "Dr Synthetic Consultant (Fictional)"),
                ("Diagnosis", "Acute appendicitis"),
                ("Procedure", "Laparoscopic appendectomy on 14/03/2026"),
            ]
        ),
        Paragraph("Course in hospital", HEADING),
        Paragraph("Fictional narrative: admitted with abdominal pain. Recovery was uneventful.", BODY),
        Paragraph("Advice on discharge", HEADING),
        Paragraph("Fictional advice: review in the outpatient clinic after seven days.", BODY),
    ]


def scanned_page(title: str, lines: list[str], size: tuple[int, int] = (1240, 1754)) -> Image.Image:
    """Draws a plain page standing in for a scan, with the same watermark as the PDFs."""
    image = Image.new("RGB", size, (252, 251, 247))
    draw = ImageDraw.Draw(image)
    heading = ImageFont.load_default(44)
    body = ImageFont.load_default(30)
    draw.text((90, 90), title, fill=(25, 25, 25), font=heading)
    for index, line in enumerate(lines):
        draw.text((90, 200 + index * 56), line, fill=(40, 40, 40), font=body)
    mark = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(mark).text((130, size[1] // 2), WATERMARK, fill=(190, 25, 25, 80), font=ImageFont.load_default(72))
    image.paste(mark.rotate(35), (0, 0), mark.rotate(35))
    draw.text((90, size[1] - 70), FOOTER, fill=(120, 120, 120), font=ImageFont.load_default(22))
    return image


def write_invalid_files(directory: Path) -> None:
    """Files that must be refused at upload. None of them contains active content."""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "spoofed-text-named-as.pdf").write_text("This is plain text with a .pdf name.\n")
    (directory / "spoofed-html-named-as.png").write_text("<html><body>Not an image.</body></html>\n")
    (directory / "empty.pdf").write_bytes(b"")

    source = (OUTPUT / "claims/appendectomy/hospital-bill.pdf").read_bytes()
    (directory / "truncated.pdf").write_bytes(source[: len(source) // 3])

    write_pdf(
        directory / "password-protected.pdf",
        [Paragraph("This synthetic file requires a password.", BODY)],
        encrypt="synthetic-password",
    )
    with zipfile.ZipFile(directory / "unsupported-office-file.docx", "w") as archive:
        archive.writestr("note.txt", "Synthetic file in an unsupported format.")


def write_oversized_file(path: Path) -> None:
    """A valid PDF above the claim limit, made large with a trailing comment."""
    source = (OUTPUT / "claims/appendectomy/hospital-bill.pdf").read_bytes()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(source + b"\n%" + b"x" * (26 * 1024 * 1024) + b"\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oversized", action="store_true", help="also write a 26 MB file into .data/")
    arguments = parser.parse_args()

    documents: dict[str, Callable[[], list[Any]]] = {
        "policies/family-floater-gold-v1.pdf": lambda: policy_wording(1),
        "policies/family-floater-gold-v2.pdf": lambda: policy_wording(2),
        "claims/appendectomy/claim-form.pdf": lambda: claim_form("Synthetic Patient Asha", "1,06,000.00"),
        "claims/appendectomy/hospital-bill.pdf": lambda: hospital_bill("Synthetic Patient Asha"),
        "claims/appendectomy/discharge-summary.pdf": lambda: discharge_summary("Synthetic Patient Asha"),
    }
    for name, build in documents.items():
        write_pdf(OUTPUT / name, build())

    scans = OUTPUT / "claims/scanned"
    scans.mkdir(parents=True, exist_ok=True)
    prescription = ["Patient: Synthetic Patient Asha", "Date: 17/03/2026", "Tab Synthetic-500, twice daily for 5 days"]
    scanned_page("Prescription (Fictional)", prescription).save(scans / "prescription.png")
    report = ["Patient: Synthetic Patient Asha", "Test: Ultrasound abdomen", "Impression: Fictional finding"]
    scanned_page("Diagnostic Report (Fictional)", report).save(scans / "diagnostic-report.jpg", quality=80)
    pages = [
        scanned_page("Scanned Discharge Note: Page 1", ["Patient: Synthetic Patient Asha", "Admission: 14/03/2026"]),
        scanned_page("Scanned Discharge Note: Page 2", ["Discharge: 17/03/2026", "Condition: Stable (fictional)"]),
    ]
    pages[0].save(scans / "discharge-note.tiff", save_all=True, append_images=pages[1:], compression="tiff_lzw")

    write_invalid_files(OUTPUT / "invalid")
    if arguments.oversized:
        write_oversized_file(ROOT / ".data/oversized-synthetic.pdf")
    print(f"Synthetic documents written to {OUTPUT}")


if __name__ == "__main__":
    main()
