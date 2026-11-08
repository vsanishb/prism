"""Isolation Forest and Local Outlier Factor, fitted on the synthetic baseline (D-083, D-084).

Each detector is kept separate, so that its result can be shown and evaluated on its own. A score is
a measure of how unusual a claim is compared with the baseline. It is not a probability of anything,
least of all fraud.
"""

import hashlib
import json
from dataclasses import dataclass
from enum import StrEnum
from functools import cache
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray
from sklearn.ensemble import IsolationForest
from sklearn.neighbors import LocalOutlierFactor

from prism_ai.risk import synthetic
from prism_ai.risk.features import FEATURE_NAMES, FEATURE_VERSION, transform

MODEL_VERSION = "anomaly-1"
MANIFEST_PATH = Path(__file__).with_name("anomaly-1.manifest.json")
# Flags about 1 in 100 baseline claims. Chosen for a review queue that can absorb that rate.
FLAG_QUANTILE = 0.99
# Recomputed thresholds may differ from the manifest only by floating-point noise across machines.
THRESHOLD_TOLERANCE = 1e-6
FOREST_PARAMETERS: dict[str, Any] = {"n_estimators": 200, "max_samples": 256, "random_state": 7310}
LOF_PARAMETERS: dict[str, Any] = {"n_neighbors": 35, "novelty": True}


class Detector(StrEnum):
    ISOLATION_FOREST = "ISOLATION_FOREST"
    LOCAL_OUTLIER_FACTOR = "LOCAL_OUTLIER_FACTOR"


@dataclass(frozen=True)
class DetectorScore:
    detector: Detector
    score: float
    threshold: float
    percentile: float
    flagged: bool


@dataclass(frozen=True)
class FeatureComparison:
    name: str
    value: float
    baseline_median: float
    baseline_percentile: float


class ManifestMismatch(RuntimeError):
    """The fitted models differ from the recorded ones. Retrain and review before serving."""


def _fingerprint(raw: NDArray[np.float64]) -> str:
    # Rounded, so that the last bit of a logarithm on another CPU cannot change it.
    content = (
        json.dumps(
            {
                "generator": synthetic.GENERATOR_VERSION,
                "features": FEATURE_VERSION,
                "forest": FOREST_PARAMETERS,
                "lof": LOF_PARAMETERS,
                "quantile": FLAG_QUANTILE,
            },
            sort_keys=True,
        ).encode()
        + np.round(raw, 6).astype("<f8").tobytes()
    )
    return hashlib.sha256(content).hexdigest()


class AnomalyModels:
    def __init__(self, baseline_raw: NDArray[np.float64]) -> None:
        self.baseline_size = baseline_raw.shape[0]
        self.fingerprint = _fingerprint(baseline_raw)
        transformed = transform(baseline_raw)
        self._mean = transformed.mean(axis=0)
        self._scale = transformed.std(axis=0)
        scaled = self._scaled(transformed)
        self._forest = IsolationForest(**FOREST_PARAMETERS).fit(scaled)
        self._lof = LocalOutlierFactor(**LOF_PARAMETERS).fit(scaled)
        # Training points are scored by LOF without counting themselves as neighbors.
        self._baseline_scores = {
            Detector.ISOLATION_FOREST: np.sort(-self._forest.score_samples(scaled)),
            Detector.LOCAL_OUTLIER_FACTOR: np.sort(-self._lof.negative_outlier_factor_),
        }
        self.thresholds = {
            detector: float(np.quantile(scores, FLAG_QUANTILE))
            for detector, scores in self._baseline_scores.items()
        }
        self._baseline_raw = np.sort(baseline_raw, axis=0)
        self.medians = np.median(baseline_raw, axis=0)

    def _scaled(self, transformed: NDArray[np.float64]) -> NDArray[np.float64]:
        return (transformed - self._mean) / self._scale

    def raw_scores(self, raw: NDArray[np.float64]) -> dict[Detector, NDArray[np.float64]]:
        scaled = self._scaled(transform(raw))
        return {
            Detector.ISOLATION_FOREST: -self._forest.score_samples(scaled),
            Detector.LOCAL_OUTLIER_FACTOR: -self._lof.score_samples(scaled),
        }

    def score(self, row: NDArray[np.float64]) -> list[DetectorScore]:
        scores = self.raw_scores(row.reshape(1, -1))
        results = []
        for detector in Detector:
            value = float(scores[detector][0])
            baseline = self._baseline_scores[detector]
            percentile = 100.0 * float(np.searchsorted(baseline, value, side="right")) / baseline.size
            results.append(
                DetectorScore(
                    detector, value, self.thresholds[detector], percentile, value > self.thresholds[detector]
                )
            )
        return results

    def compare(self, row: NDArray[np.float64]) -> list[FeatureComparison]:
        """Where each value sits in the baseline: descriptive context, not the detectors' reasoning."""
        comparisons = []
        for index, name in enumerate(FEATURE_NAMES):
            column = self._baseline_raw[:, index]
            below = np.searchsorted(column, row[index], side="left")
            at_or_below = np.searchsorted(column, row[index], side="right")
            percentile = 100.0 * float(below + at_or_below) / (2 * column.size)
            comparisons.append(
                FeatureComparison(name, float(row[index]), float(self.medians[index]), percentile)
            )
        return comparisons

    def manifest(self) -> dict[str, Any]:
        return {
            "modelVersion": MODEL_VERSION,
            "featureVersion": FEATURE_VERSION,
            "generatorVersion": synthetic.GENERATOR_VERSION,
            "seed": synthetic.BASELINE_SEED,
            "baselineSize": self.baseline_size,
            "flagQuantile": FLAG_QUANTILE,
            "isolationForest": FOREST_PARAMETERS,
            "localOutlierFactor": LOF_PARAMETERS,
            "fingerprint": self.fingerprint,
            "thresholds": {detector.value: threshold for detector, threshold in self.thresholds.items()},
        }

    def verify(self, recorded: dict[str, Any]) -> None:
        current = self.manifest()
        for key, value in current.items():
            if key == "thresholds":
                continue
            if recorded.get(key) != value:
                raise ManifestMismatch(f"Anomaly model {key} differs from the manifest")
        for detector, threshold in current["thresholds"].items():
            expected = float(recorded["thresholds"][detector])
            if abs(threshold - expected) > THRESHOLD_TOLERANCE * max(1.0, abs(expected)):
                raise ManifestMismatch(f"{detector} threshold differs from the manifest")


def fit() -> AnomalyModels:
    return AnomalyModels(synthetic.baseline().features())


@cache
def load_models() -> AnomalyModels:
    """Fitted once per process, and refused if it does not reproduce the recorded manifest."""
    models = fit()
    models.verify(json.loads(MANIFEST_PATH.read_text(encoding="utf-8")))
    return models
