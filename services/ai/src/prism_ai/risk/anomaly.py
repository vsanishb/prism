"""Scores one claim's features with both detectors. Pure computation on numbers: no model calls,
no storage, and nothing about who the claim concerns."""

import numpy as np

from prism_ai.risk import synthetic
from prism_ai.risk.detectors import MODEL_VERSION, AnomalyModels
from prism_ai.risk.features import FEATURE_NAMES
from prism_ai.risk.schemas import AnomalyRequest, AnomalyResponse, DetectorResult, FeatureResult


def score(models: AnomalyModels, request: AnomalyRequest) -> AnomalyResponse:
    given = {feature.name: feature.value for feature in request.features}
    row = np.array(
        [
            given[name] if given[name] is not None else models.medians[index]
            for index, name in enumerate(FEATURE_NAMES)
        ],
        dtype=np.float64,
    )
    comparisons = models.compare(row)
    return AnomalyResponse(
        claim_id=request.claim_id,
        model_version=MODEL_VERSION,
        generator_version=synthetic.GENERATOR_VERSION,
        fingerprint=models.fingerprint,
        baseline_size=models.baseline_size,
        detectors=[
            DetectorResult(
                detector=result.detector,
                score=round(result.score, 6),
                threshold=round(result.threshold, 6),
                percentile=round(result.percentile, 2),
                flagged=result.flagged,
            )
            for result in models.score(row)
        ],
        features=[
            FeatureResult(
                name=comparison.name,
                value=comparison.value,
                imputed=given[comparison.name] is None,
                baseline_median=round(comparison.baseline_median, 4),
                baseline_percentile=round(comparison.baseline_percentile, 2),
            )
            for comparison in comparisons
        ],
    )
