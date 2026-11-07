"""What Prism extracts from each document type. Every field gets an explicit state, so that an
absent value is recorded as absent rather than left out."""

from dataclasses import dataclass
from enum import StrEnum


class DocumentType(StrEnum):
    CLAIM_FORM = "CLAIM_FORM"
    HOSPITAL_BILL = "HOSPITAL_BILL"
    ITEMIZED_INVOICE = "ITEMIZED_INVOICE"
    DISCHARGE_SUMMARY = "DISCHARGE_SUMMARY"
    PRESCRIPTION = "PRESCRIPTION"
    DIAGNOSTIC_REPORT = "DIAGNOSTIC_REPORT"
    OTHER = "OTHER"


class ValueKind(StrEnum):
    TEXT = "TEXT"
    DATE = "DATE"
    AMOUNT = "AMOUNT"


@dataclass(frozen=True)
class FieldSpec:
    name: str
    kind: ValueKind
    critical: bool
    description: str


POLICY_NUMBER = FieldSpec("policy_number", ValueKind.TEXT, True, "Insurance policy number")
CLAIMANT_NAME = FieldSpec("claimant_name", ValueKind.TEXT, False, "Person making the claim")
PATIENT_NAME = FieldSpec("patient_name", ValueKind.TEXT, True, "Person who was treated")
HOSPITAL_NAME = FieldSpec("hospital_name", ValueKind.TEXT, True, "Hospital or clinic name")
ADMISSION_DATE = FieldSpec("admission_date", ValueKind.DATE, True, "Date of admission")
DISCHARGE_DATE = FieldSpec("discharge_date", ValueKind.DATE, True, "Date of discharge")
CLAIMED_AMOUNT = FieldSpec("claimed_amount", ValueKind.AMOUNT, True, "Total amount claimed")
BILL_NUMBER = FieldSpec("bill_number", ValueKind.TEXT, False, "Bill or invoice number")
BILL_DATE = FieldSpec("bill_date", ValueKind.DATE, False, "Date on the bill or invoice")
TOTAL_AMOUNT = FieldSpec("total_amount", ValueKind.AMOUNT, True, "Final total stated on the bill or invoice")
DIAGNOSIS = FieldSpec("diagnosis", ValueKind.TEXT, True, "Diagnosis exactly as documented. Never infer one")
PROCEDURE = FieldSpec("procedure", ValueKind.TEXT, False, "Procedure performed, as documented")
TREATING_DOCTOR = FieldSpec("treating_doctor", ValueKind.TEXT, False, "Treating or prescribing doctor")
DOCUMENT_DATE = FieldSpec("document_date", ValueKind.DATE, False, "Date the document was issued")
TEST_NAME = FieldSpec("test_name", ValueKind.TEXT, False, "Name of the test or investigation")
FINDINGS = FieldSpec("findings", ValueKind.TEXT, False, "Stated impression or findings, as documented")
MEDICINES = FieldSpec("medicines", ValueKind.TEXT, False, "Medicines prescribed, as written")

CATALOG: dict[DocumentType, tuple[FieldSpec, ...]] = {
    DocumentType.CLAIM_FORM: (
        POLICY_NUMBER, CLAIMANT_NAME, PATIENT_NAME, HOSPITAL_NAME,
        ADMISSION_DATE, DISCHARGE_DATE, CLAIMED_AMOUNT,
    ),
    DocumentType.HOSPITAL_BILL: (
        BILL_NUMBER, BILL_DATE, PATIENT_NAME, HOSPITAL_NAME,
        ADMISSION_DATE, DISCHARGE_DATE, TOTAL_AMOUNT,
    ),
    DocumentType.ITEMIZED_INVOICE: (
        BILL_NUMBER, BILL_DATE, PATIENT_NAME, HOSPITAL_NAME, TOTAL_AMOUNT,
    ),
    DocumentType.DISCHARGE_SUMMARY: (
        PATIENT_NAME, HOSPITAL_NAME, ADMISSION_DATE, DISCHARGE_DATE,
        TREATING_DOCTOR, DIAGNOSIS, PROCEDURE,
    ),
    DocumentType.PRESCRIPTION: (PATIENT_NAME, TREATING_DOCTOR, DOCUMENT_DATE, DIAGNOSIS, MEDICINES),
    DocumentType.DIAGNOSTIC_REPORT: (PATIENT_NAME, HOSPITAL_NAME, DOCUMENT_DATE, TEST_NAME, FINDINGS),
    DocumentType.OTHER: (PATIENT_NAME, HOSPITAL_NAME, DOCUMENT_DATE),
}

TYPES_WITH_LINE_ITEMS = frozenset({DocumentType.HOSPITAL_BILL, DocumentType.ITEMIZED_INVOICE})


def fields_for(document_type: DocumentType) -> tuple[FieldSpec, ...]:
    return CATALOG[document_type]
