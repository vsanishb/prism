"""Measures the detectors on held-out synthetic claims. Labels are used here and nowhere else.

These numbers describe agreement with the generator's own idea of normal and anomalous. They say
nothing about how the detectors would do on real claims, or about fraud.
"""

from dataclasses import dataclass

import numpy as np
from sklearn.metrics import roc_auc_score

from prism_ai.risk import synthetic
from prism_ai.risk.detectors import AnomalyModels, Detector
from prism_ai.risk.synthetic import AnomalyType


@dataclass(frozen=True)
class DetectorEvaluation:
    detector: str
    false_positive_rate: float
    recall: float
    roc_auc: float
    recall_by_type: dict[str, float]


@dataclass(frozen=True)
class Evaluation:
    held_out_size: int
    anomaly_size: int
    detectors: list[DetectorEvaluation]
    either_recall: float
    either_false_positive_rate: float
    both_recall_by_type: dict[str, float]


def evaluate(models: AnomalyModels) -> Evaluation:
    normal = synthetic.held_out().features()
    unusual, labels = synthetic.anomalies()
    normal_scores = models.raw_scores(normal)
    unusual_scores = models.raw_scores(unusual)
    truth = np.concatenate([np.zeros(normal.shape[0]), np.ones(unusual.shape[0])])
    kinds = np.array([label.value for label in labels])

    results = []
    flagged_normal = {}
    flagged_unusual = {}
    for detector in Detector:
        threshold = models.thresholds[detector]
        flagged_normal[detector] = normal_scores[detector] > threshold
        flagged_unusual[detector] = unusual_scores[detector] > threshold
        results.append(
            DetectorEvaluation(
                detector=detector.value,
                false_positive_rate=float(flagged_normal[detector].mean()),
                recall=float(flagged_unusual[detector].mean()),
                roc_auc=float(
                    roc_auc_score(truth, np.concatenate([normal_scores[detector], unusual_scores[detector]]))
                ),
                recall_by_type={
                    kind.value: float(flagged_unusual[detector][kinds == kind.value].mean())
                    for kind in AnomalyType
                },
            )
        )
    either_normal = flagged_normal[Detector.ISOLATION_FOREST] | flagged_normal[Detector.LOCAL_OUTLIER_FACTOR]
    either_unusual = (
        flagged_unusual[Detector.ISOLATION_FOREST] | flagged_unusual[Detector.LOCAL_OUTLIER_FACTOR]
    )
    both_unusual = flagged_unusual[Detector.ISOLATION_FOREST] & flagged_unusual[Detector.LOCAL_OUTLIER_FACTOR]
    return Evaluation(
        held_out_size=normal.shape[0],
        anomaly_size=unusual.shape[0],
        detectors=results,
        either_recall=float(either_unusual.mean()),
        either_false_positive_rate=float(either_normal.mean()),
        both_recall_by_type={
            kind.value: float(both_unusual[kinds == kind.value].mean()) for kind in AnomalyType
        },
    )
