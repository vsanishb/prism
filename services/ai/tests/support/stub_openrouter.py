"""A local stand-in for OpenRouter, so that end-to-end tests run the real AI service and gateway
without paying for or depending on real models. It knows the synthetic test documents by a
distinctive piece of their text. Test tooling only: it is never imported by the service.

    python -m uv run python -m tests.support.stub_openrouter
"""

import asyncio
import json
import os
from typing import Any

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from prism_ai.documents.catalog import DocumentType, fields_for
from prism_ai.gateway.routes import load_routes

PORT = 8099
FAIL_ONCE_MARKER = "SCH-IP-2026-007300"
# Lets restart tests kill services while a model call is in flight.
DELAY_SECONDS = float(os.environ.get("PRISM_STUB_DELAY_SECONDS", "0"))

app = FastAPI()
fail_next = True

HOSPITAL = "Synthetic Care Hospital, Pune (Fictional)"
PATIENT = "Synthetic Patient Asha"


def stated(value: str, quote: str, page: int = 1) -> dict[str, Any]:
    return {"state": "PRESENT", "value": value, "confidence": "HIGH", "page": page, "quote": quote}


MISSING = {"state": "MISSING", "value": "", "confidence": "HIGH", "page": 0, "quote": ""}


def item(description: str, quantity: str, rate: str, amount: str) -> dict[str, Any]:
    return {
        "description": description,
        "quantity": quantity,
        "unit_price": rate,
        "amount": amount,
        "page": 1,
        "quote": f"{description} {quantity} {rate} {amount}",
    }


CLAIM_FORM = {
    "policy_number": stated("SYN-FFG-2026-0001", "Policy number SYN-FFG-2026-0001"),
    "claimant_name": stated("Synthetic Policyholder One", "Claimant Synthetic Policyholder One"),
    "patient_name": stated(PATIENT, f"Patient {PATIENT}"),
    "hospital_name": stated(HOSPITAL, f"Hospital {HOSPITAL}"),
    "admission_date": stated("14/03/2026", "Admission date 14/03/2026"),
    "discharge_date": stated("17/03/2026", "Discharge date 17/03/2026"),
    "claimed_amount": stated("1,06,000.00", "Total amount claimed INR 1,06,000.00"),
}


def bill(number: str, total: str) -> dict[str, Any]:
    return {
        "bill_number": stated(number, f"Bill number {number}"),
        "bill_date": stated("17/03/2026", "Bill date 17/03/2026"),
        "patient_name": stated(PATIENT, f"Patient {PATIENT}"),
        "hospital_name": stated(HOSPITAL, HOSPITAL),
        "admission_date": stated("14/03/2026", "Admission to discharge 14/03/2026"),
        "discharge_date": stated("17/03/2026", "14/03/2026 to 17/03/2026"),
        "total_amount": stated(total, f"Total {total}"),
    }


def discharge(admission: str, procedure_date: str) -> dict[str, Any]:
    return {
        "patient_name": stated(PATIENT, f"Patient {PATIENT}"),
        "hospital_name": stated(HOSPITAL, HOSPITAL),
        "admission_date": stated(admission, f"Admission {admission}"),
        "discharge_date": stated("17/03/2026", "Discharge 17/03/2026"),
        "treating_doctor": stated(
            "Dr Synthetic Consultant (Fictional)", "Dr Synthetic Consultant (Fictional)"
        ),
        "diagnosis": stated("Acute appendicitis", "Diagnosis Acute appendicitis"),
        "procedure": stated(
            f"Laparoscopic appendectomy on {procedure_date}", f"Laparoscopic appendectomy on {procedure_date}"
        ),
    }


APPENDECTOMY_ITEMS = [
    item("Room rent (single private)", "3", "6,500.00", "19,500.00"),
    item("Surgeon fee: laparoscopic appendectomy", "1", "42,000.00", "42,000.00"),
    item("Anaesthetist fee", "1", "12,000.00", "12,000.00"),
    item("Operation theatre charges", "1", "18,500.00", "18,500.00"),
    item("Pharmacy and consumables", "1", "9,250.00", "9,250.00"),
    item("Laboratory investigations", "1", "4,750.00", "4,750.00"),
]
MISMATCHED_ITEMS = [
    item("Room rent", "3", "6,500.00", "19,500.00"),
    item("Surgeon fee", "1", "42,000.00", "42,000.00"),
    item("Operation theatre charges", "1", "43,500.00", "43,500.00"),
]

# (marker in the document's text, document type, fields, line items)
KNOWN_DOCUMENTS: list[tuple[str, DocumentType, dict[str, Any], list[dict[str, Any]]]] = [
    ("Health Claim Form", DocumentType.CLAIM_FORM, CLAIM_FORM, []),
    (
        "SCH-IP-2026-004417",
        DocumentType.HOSPITAL_BILL,
        bill("SCH-IP-2026-004417", "1,06,000.00"),
        APPENDECTOMY_ITEMS,
    ),
    (
        "SCH-IP-2026-004418",
        DocumentType.HOSPITAL_BILL,
        bill("SCH-IP-2026-004418", "1,06,000.00"),
        MISMATCHED_ITEMS,
    ),
    ("Admission 15/03/2026", DocumentType.DISCHARGE_SUMMARY, discharge("15/03/2026", "15/03/2026"), []),
    ("Admission 14/03/2026", DocumentType.DISCHARGE_SUMMARY, discharge("14/03/2026", "14/03/2026"), []),
    (FAIL_ONCE_MARKER, DocumentType.HOSPITAL_BILL, {}, []),
]
SCAN_FIELDS: dict[str, Any] = {
    "patient_name": stated(PATIENT, f"Patient: {PATIENT}"),
    "document_date": stated("17/03/2026", "Date: 17/03/2026"),
    "medicines": stated(
        "Tab Synthetic-500, twice daily for 5 days", "Tab Synthetic-500, twice daily for 5 days"
    ),
}


def document_text(body: dict[str, Any]) -> str:
    return json.dumps(body["messages"][-1]["content"])


def known_document(text: str) -> tuple[DocumentType, dict[str, Any], list[dict[str, Any]]] | None:
    for marker, document_type, fields, items in KNOWN_DOCUMENTS:
        if marker in text:
            return document_type, fields, items
    return None


def classification(text: str) -> dict[str, Any]:
    known = known_document(text)
    document_type = known[0] if known else DocumentType.PRESCRIPTION
    return {"document_type": document_type.value, "confidence": "HIGH"}


def extraction(body: dict[str, Any], text: str) -> dict[str, Any]:
    known = known_document(text)
    schema = body["response_format"]["json_schema"]["schema"]
    names = schema["properties"]["fields"]["required"]
    fields = known[1] if known else SCAN_FIELDS
    return {
        "fields": {name: fields.get(name, MISSING) for name in names},
        "line_items": known[2] if known else [],
    }


def reread(body: dict[str, Any]) -> dict[str, Any]:
    names = body["response_format"]["json_schema"]["schema"]["properties"]["fields"]["required"]
    return {"fields": {name: {"value": SCAN_FIELDS.get(name, MISSING)["value"]} for name in names}}


def completion(content: dict[str, Any]) -> dict[str, Any]:
    return {
        "provider": "Stub",
        "choices": [
            {"finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps(content)}}
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "cost": 0},
    }


@app.get("/api/v1/endpoints/zdr")
def catalog() -> dict[str, Any]:
    routes = load_routes()
    models = {model for route in routes.tasks.values() for model in route.models()}
    return {"data": [{"model_id": model, "safety_retention": False} for model in models]}


@app.post("/api/v1/chat/completions", response_model=None)
async def chat(request: Request) -> dict[str, Any] | JSONResponse:
    body = await request.json()
    schema_name = body["response_format"]["json_schema"]["name"]
    text = document_text(body)
    if schema_name == "document_classification":
        return completion(classification(text))
    if schema_name == "document_reread":
        return completion(reread(body))
    await asyncio.sleep(DELAY_SECONDS)
    # Alternates for this document, so that every test run sees one failure and then a good retry.
    if FAIL_ONCE_MARKER in text:
        global fail_next
        fail_next = not fail_next
        if not fail_next:
            return JSONResponse({"error": {"code": 400}}, status_code=400)
        names = body["response_format"]["json_schema"]["schema"]["properties"]["fields"]["required"]
        return completion({"fields": dict.fromkeys(names, MISSING), "line_items": []})
    return completion(extraction(body, text))


if __name__ == "__main__":
    # Every field is defined, so that a schema change breaks this stub loudly at startup.
    assert all(fields_for(document_type) for document_type in DocumentType)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
