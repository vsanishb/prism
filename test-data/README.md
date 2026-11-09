# Claims Data

**Everything here is synthetic.** Every person, hospital, insurer, and policy is invented, with names chosen to avoid real ones ([D-100](../docs/decisions.md)). The documents carry no watermark, so that the system looks as it would in use; they must never be presented as real insurance or medical documents outside this project.

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
| `claims/risk-demo/` | The M7 demonstrations: severe, clean, and disguised claims, the earlier claims they are compared with, and a manifest. Regenerate with `scripts/generate_risk_demo.py` |
| `claims/demo-corpus/` | The presentation set: 25 claims covering every risk rule and every review state, with a manifest of the rules each should raise. Loaded by `prism.py demo-prepare`. Regenerate with `scripts/generate_demo.py`, which also writes `demo/` and the Silver and Plus wordings in `policies/` |
| `invalid/` | Files that upload validation must refuse |

Later milestones add their own scenarios.
