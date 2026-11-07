# Document Intelligence

Turns claim documents into structured, source-linked facts. AI side: `services/ai/src/prism_ai`. Core side: `dev.prism.core.intelligence`. Design: [plan](../plans/document-intelligence.md).

## Flow

1. Core sends the file, as base64 in signed JSON, to `POST /internal/v1/documents/extract`.
2. A killable child process opens it and renders eight pages at a time ([D-059](../decisions.md)).
3. The gateway classifies the document, then extracts each window.
4. Code grounds, normalizes, and decides each value's state ([D-060](../decisions.md)).
5. Core stores the result, then consolidates and validates across documents.

## AI service

| Package | Purpose |
|---|---|
| `gateway/client.py` | The only path to models. Repair, escalation, failure classification, usage |
| `gateway/retention.py` | Refuses models that are not provably retention-free. Fails closed |
| `gateway/routes.toml` | Model routes |
| `documents/render.py`, `render_worker.py` | Parsing in a child process, under time and memory limits |
| `documents/catalog.py` | Fields per document type |
| `documents/normalize.py` | Dates and amounts, in code |
| `documents/ground.py` | Finds quotes in the text layer, and produces highlight regions |
| `documents/pipeline.py` | Decides what may be `PRESENT` |
| `prompts/*.v1.md` | Versioned prompts. A change needs a new version and an evaluation run |

**Failure codes** returned to core: `model_unavailable` and `ai_service_*` retry; `model_rejected`, `model_output_invalid`, `route_not_permitted`, and `document_unreadable` do not.

## Rules for `PRESENT`

A value is `PRESENT` only if all of these hold. Otherwise it is `UNCERTAIN`, and the reason is stored.

- The model gave a value, a page in this window, and a quote.
- With a text layer: the quote is found on that page, and the value is inside the quote.
- The value parses for its kind.
- On a scan: critical values match an independent second reading by a different model.

A diagnosis is recorded only as written, and the database refuses a `PRESENT` source with no citation.

## Core

| Type | Purpose |
|---|---|
| `DefaultClaimIntelligence` | Extraction and consolidation. Holds no transaction during model calls |
| `FactConsolidator` | Pure merge. Documents that disagree give `CONFLICTING`, with every source kept |
| `ClaimValidator` | Pure checks, listed below |
| `IntelligenceStore` | Insert-only. The runtime role cannot update or delete derived records |
| `ClaimFactsService` | `GET /api/v1/claims/{id}/facts`, needing `CLAIM_READ` and claim visibility |

**Findings:** `MISSING_REQUIRED_DOCUMENT` (the only one that holds a claim), `VALUES_CONFLICT`, `VALUE_UNCERTAIN`, `VALUE_MISSING`, `DISCHARGE_BEFORE_ADMISSION`, `DIFFERS_FROM_ENTERED_DETAILS`, `LINE_ITEMS_DO_NOT_MATCH_TOTAL`, `LINE_ITEMS_UNVERIFIED`, `CLAIMED_AMOUNT_DIFFERS_FROM_BILLS`, `DOCUMENT_TYPE_DIFFERS`. None decides anything.

**Required documents:** reimbursement needs a claim form, hospital bill, and discharge summary. Cashless needs the bill and summary. Whether a document counts depends on what it reads as, when that reading is confident.

## Evaluation

Golden set: `services/ai/evals/claim_extraction.json`, 17 synthetic documents with dense tables, a degraded scan, script-font handwriting, absent fields, injected instructions, and conflicts. Run with:

```bash
python -m uv run python evals/run_claim_extraction.py
```

Results on 2026-11-07, pipeline `doc-1`:

| Metric | Target | Sonnet 5.5 | Haiku 5.5 |
|---|---|---|---|
| Critical field accuracy | ≥ 95% | 64/64 | 64/64 |
| Monetary accuracy | ≥ 98% | 8/8 | 8/8 |
| Invented values on absent fields | 0 | 0 of 17 | 0 of 17 |
| Injected values returned | 0 | 0 | 0 |
| Classification | n/a | 17/17 | 17/17 |
| Complete line item sets | n/a | 8/8 | 8/8 |
| Cost for the set | n/a | $0.33 | $0.03 |

**Read these with care.** The set is small and clean, the "handwriting" is a script font, and the prompts and normalizer were corrected against it, so a perfect score does not predict real-document accuracy. It needs harder and larger samples before any real use.

## Not built

- Semantic contradictions, such as a diagnosis that does not fit a procedure. Only stated values are compared. Model-proposed contradictions belong to M7 ([D-028](../decisions.md)).
- Fact correction (M8). Currency is assumed to be the claim's: documents' currencies are not read.
- Line items on scans are not re-read. A wrong amount there is caught only by the total check.
