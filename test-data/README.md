# Synthetic Test Data

**Everything here is fictional.** Every person, hospital, insurer, and policy is invented, and every page is watermarked. These files must never be presented as real insurance or medical documents.

Regenerate with:

```bash
python -m uv run --project services/ai python scripts/generate_test_data.py
```

| Path | Purpose |
|---|---|
| `policies/` | Two versions of a fictional policy, with limits, waiting periods, exclusions, and cross-references |
| `claims/appendectomy/` | A consistent claim: form, itemized bill, discharge summary |
| `claims/scanned/` | Image documents: PNG, JPEG, and a two-page TIFF |
| `claims/conflicting/` | Disagrees with `appendectomy` on the admission date, with charges that do not add up |
| `claims/hard/` | Evaluation hard cases: dense, degraded, handwriting-style, sparse, injected. Generating these needs a Windows script font |
| `invalid/` | Files that upload validation must refuse |

Later milestones add their own scenarios, such as anomalous claims for M7.
