# Document Intelligence

## Purpose
Turn heterogeneous claim documents into structured, source-linked facts, without inventing anything.

## Scope
**In:** model gateway, classification, vision extraction, grounding, consolidation, validation.
**Out:** policy documents (M4), risk rules (M7).

## Design considerations

**Document types (initial):** claim form, hospital bill, itemized invoice, discharge summary, prescription, diagnostic report, other.

**Extraction contract.** Each type has a Pydantic schema, and each value is wrapped:

```json
{
  "value": "2026-03-14",
  "state": "PRESENT",
  "confidence": "HIGH",
  "source": { "page": 2, "quote": "Date of Admission: 14/03/2026", "region": [0.12, 0.31, 0.48, 0.34] }
}
```

- `state` is `PRESENT`, `MISSING`, `UNCERTAIN`, or `CONFLICTING`.
- Models must quote the source. A value without a source cannot be `PRESENT`.
- Diagnoses are captured only as documented, and are never inferred.

**Grounding.** Model-reported coordinates are unreliable. Where a text layer exists, the quote is located in it to get a region. For scans, provenance stays at page level, plus a re-read check of critical values. An ungrounded critical value becomes `UNCERTAIN`.

**Consolidation.** Facts merge across documents in deterministic code. Disagreements become `CONFLICTING`, with all sources kept. No value is silently chosen.

**Validation.** Date logic (admission before discharge), line items against totals, required fields per type, required documents per claim. Findings are stored with severity.

**Model gateway.** Applies routing from [D-026](../decisions.md), mandatory ZDR for claim data, and structured output. Malformed output gets one repair attempt, then escalates, then fails the document explicitly. Records usage for each attempt.

## Dependencies
M3 workflow. `OPENROUTER_API_KEY`.

## Components
| Component | Responsibility |
|---|---|
| `gateway` | Model access |
| `documents/render` | Page images and text layer, with resource limits |
| `documents/classify` | Document type |
| `documents/extract` | Type-specific extraction |
| `documents/ground` | Quote matching and region resolution |
| Core `intelligence` | Persists extractions, consolidation, validation |
| Web | Extraction review with source highlighting |

## Testing
Scripted-gateway tests for all failure paths; consolidation and validation unit tests; injection fixtures; the evaluation set with targets in [testing.md](../testing.md#testing-ai-components).

## Completion criteria
Evaluation targets are met, zero values are invented on absent fields, and every `PRESENT` fact links to its source in the UI.
