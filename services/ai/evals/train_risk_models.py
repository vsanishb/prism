"""Fits the anomaly detectors on the synthetic baseline, records the manifest the service checks at
startup (D-084), and evaluates them on held-out synthetic claims. No model calls, no cost.

    python -m uv run python evals/train_risk_models.py          # check the manifest, then evaluate
    python -m uv run python evals/train_risk_models.py --write  # record a deliberately changed model

All data is synthetic. The results describe the generator, not real claims.
"""

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from prism_ai.risk.detectors import MANIFEST_PATH, ManifestMismatch, fit
from prism_ai.risk.evaluation import evaluate
from prism_ai.risk.synthetic import ANOMALY_DESCRIPTIONS

RESULTS = Path(__file__).parent / "results" / "risk_anomaly.json"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="record the fitted models as the new manifest")
    arguments = parser.parse_args()

    models = fit()
    if arguments.write:
        MANIFEST_PATH.write_text(json.dumps(models.manifest(), indent=2) + "\n", encoding="utf-8")
        print(f"Manifest written: {MANIFEST_PATH.name}")
    else:
        try:
            models.verify(json.loads(MANIFEST_PATH.read_text(encoding="utf-8")))
        except ManifestMismatch as error:
            sys.exit(f"{error}. If the change is deliberate, run with --write and review the evaluation.")
        print("Fitted models match the manifest.")

    result = evaluate(models)
    print(f"\nHeld-out normal claims: {result.held_out_size}. Controlled anomalies: {result.anomaly_size}.")
    for detector in result.detectors:
        print(
            f"\n{detector.detector}: false positives {detector.false_positive_rate:.1%}, "
            f"recall {detector.recall:.1%}, ROC AUC {detector.roc_auc:.3f}"
        )
        for kind, recall in detector.recall_by_type.items():
            print(f"  {kind:<22} {recall:6.1%}  {ANOMALY_DESCRIPTIONS[kind]}")  # type: ignore[index]
    print(
        f"\nEither detector: recall {result.either_recall:.1%}, "
        f"false positives {result.either_false_positive_rate:.1%}"
    )
    print(
        "Both detectors, by type: " + ", ".join(f"{k} {v:.0%}" for k, v in result.both_recall_by_type.items())
    )
    RESULTS.parent.mkdir(exist_ok=True)
    RESULTS.write_text(json.dumps(asdict(result), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
