"""The anomaly detectors: reproducible training, the manifest check, evaluation on held-out synthetic
claims, and the demonstration scenarios' expected results. All data is synthetic (D-083)."""

import json
from typing import Any
from uuid import uuid4

import numpy as np
import pytest

from prism_ai.risk import synthetic
from prism_ai.risk.anomaly import score
from prism_ai.risk.detectors import (
    MANIFEST_PATH,
    AnomalyModels,
    Detector,
    ManifestMismatch,
    load_models,
)
from prism_ai.risk.evaluation import evaluate
from prism_ai.risk.features import FEATURE_NAMES
from prism_ai.risk.schemas import AnomalyRequest, FeatureInput
from prism_ai.risk.synthetic import AnomalyType


@pytest.fixture(scope="module")
def models() -> AnomalyModels:
    return load_models()


def features(**values: float | None) -> dict[str, float | None]:
    return dict(zip(FEATURE_NAMES, [values.get(name) for name in FEATURE_NAMES], strict=True))


def request(values: dict[str, float | None]) -> AnomalyRequest:
    return AnomalyRequest(
        claim_id=uuid4(),
        feature_version="risk-features-1",
        features=[FeatureInput(name=name, value=value) for name, value in values.items()],
    )


# The three demonstration claims, as core computes their features (docs/test-guides/m7-fraud-risk.md).
CLEAN = features(
    claimedAmount=98_400, sumInsuredRatio=0.1968, lengthOfStayDays=3, amountPerDay=32_800, lineItemCount=7,
    largestItemShare=0.4268, billDeviation=0, priorClaims12m=0, daysSinceCoverStart=187,
)  # fmt: skip
DISGUISED = features(
    claimedAmount=392_000, sumInsuredRatio=0.784, lengthOfStayDays=2, amountPerDay=196_000, lineItemCount=7,
    largestItemShare=0.4209, billDeviation=0, priorClaims12m=2, daysSinceCoverStart=260,
)  # fmt: skip
BLATANT = features(
    claimedAmount=740_000, sumInsuredRatio=1.48, lengthOfStayDays=1, amountPerDay=740_000, lineItemCount=13,
    largestItemShare=0.1751, billDeviation=0.5022, priorClaims12m=0, daysSinceCoverStart=221,
)  # fmt: skip


def flags(models: AnomalyModels, values: dict[str, float | None]) -> dict[Detector, bool]:
    return {result.detector: result.flagged for result in score(models, request(values)).detectors}


def test_the_synthetic_sets_are_reproducible_and_distinct() -> None:
    assert np.array_equal(synthetic.baseline().features(), synthetic.baseline().features())
    baseline = synthetic.baseline().features()
    held_out = synthetic.held_out().features()
    assert baseline.shape == (synthetic.BASELINE_SIZE, len(FEATURE_NAMES))
    assert held_out.shape == (synthetic.HELD_OUT_SIZE, len(FEATURE_NAMES))
    assert not np.array_equal(baseline[: held_out.shape[0]], held_out)
    rows, labels = synthetic.anomalies()
    assert rows.shape[0] == len(labels) == synthetic.ANOMALIES_PER_TYPE * len(AnomalyType)


def test_the_baseline_is_plausible() -> None:
    claims = synthetic.baseline()
    assert (claims.claimed > 0).all()
    assert (claims.claimed <= claims.bills).all(), "a normal claim never asks for more than its bills"
    assert (claims.largest_share > 0).all() and (claims.largest_share < 1).all()
    assert 20_000 < float(np.median(claims.claimed)) < 200_000


def test_fitting_reproduces_the_recorded_manifest(models: AnomalyModels) -> None:
    recorded = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    models.verify(recorded)
    assert recorded["fingerprint"] == models.fingerprint
    assert recorded["baselineSize"] == synthetic.BASELINE_SIZE


@pytest.mark.parametrize(
    "change",
    [
        {"fingerprint": "0" * 64},
        {"seed": 1},
        {"isolationForest": {"n_estimators": 10, "max_samples": 256, "random_state": 7310}},
        {"thresholds": {"ISOLATION_FOREST": 0.1, "LOCAL_OUTLIER_FACTOR": 1.416391695653013}},
    ],
)
def test_a_model_that_differs_from_the_manifest_is_refused(
    models: AnomalyModels, change: dict[str, Any]
) -> None:
    recorded = json.loads(MANIFEST_PATH.read_text(encoding="utf-8")) | change
    with pytest.raises(ManifestMismatch):
        models.verify(recorded)


def test_each_detector_flags_about_one_baseline_claim_in_a_hundred(models: AnomalyModels) -> None:
    scores = models.raw_scores(synthetic.baseline().features())
    for detector in Detector:
        rate = float((scores[detector] > models.thresholds[detector]).mean())
        assert 0.005 <= rate <= 0.015, detector


def test_scoring_is_deterministic(models: AnomalyModels) -> None:
    first = score(models, request(DISGUISED))
    second = score(models, request(DISGUISED))
    assert first.model_dump(exclude={"claim_id"}) == second.model_dump(exclude={"claim_id"})


def test_held_out_evaluation_meets_its_targets(models: AnomalyModels) -> None:
    """Targets are on synthetic data only. They show the detectors work as designed, not real accuracy."""
    result = evaluate(models)
    by_name = {detector.detector: detector for detector in result.detectors}
    forest = by_name[Detector.ISOLATION_FOREST.value]
    lof = by_name[Detector.LOCAL_OUTLIER_FACTOR.value]
    for detector in (forest, lof):
        assert detector.false_positive_rate <= 0.02
        assert detector.roc_auc >= 0.85
    assert lof.recall >= 0.85
    assert result.either_recall >= 0.90
    assert result.either_false_positive_rate <= 0.03
    assert result.both_recall_by_type[AnomalyType.SUBTLE_COMBINATION.value] >= 0.80


def test_isolation_forest_misses_inflated_claims_which_a_rule_covers(models: AnomalyModels) -> None:
    """A known limitation, kept visible: nearly every baseline claim asks for exactly its bill, so the
    forest rarely splits on that feature. The claim-above-bills rule catches these claims instead."""
    forest = next(d for d in evaluate(models).detectors if d.detector == Detector.ISOLATION_FOREST.value)
    assert forest.recall_by_type[AnomalyType.INFLATED_CLAIM.value] < 0.5


def test_the_clean_demonstration_claim_is_within_the_normal_range(models: AnomalyModels) -> None:
    assert flags(models, CLEAN) == {Detector.ISOLATION_FOREST: False, Detector.LOCAL_OUTLIER_FACTOR: False}


def test_the_disguised_demonstration_claim_is_unusual_to_both_detectors(models: AnomalyModels) -> None:
    assert flags(models, DISGUISED) == {Detector.ISOLATION_FOREST: True, Detector.LOCAL_OUTLIER_FACTOR: True}


def test_the_blatant_demonstration_claim_is_unusual_to_both_detectors(models: AnomalyModels) -> None:
    assert flags(models, BLATANT) == {Detector.ISOLATION_FOREST: True, Detector.LOCAL_OUTLIER_FACTOR: True}


def test_a_missing_feature_takes_the_baseline_median_and_says_so(models: AnomalyModels) -> None:
    result = score(models, request(CLEAN | {"lineItemCount": None}))
    item_count = next(feature for feature in result.features if feature.name == "lineItemCount")
    assert item_count.imputed
    assert item_count.value == item_count.baseline_median
    assert not any(feature.imputed for feature in result.features if feature.name != "lineItemCount")


def test_feature_comparisons_place_values_within_the_baseline(models: AnomalyModels) -> None:
    result = score(models, request(DISGUISED))
    for feature in result.features:
        assert 0 <= feature.baseline_percentile <= 100
    prior = next(feature for feature in result.features if feature.name == "priorClaims12m")
    assert prior.baseline_percentile > 97.5


def test_a_request_must_carry_every_feature_exactly_once() -> None:
    with pytest.raises(ValueError, match="features must be exactly"):
        AnomalyRequest(
            claim_id=uuid4(),
            feature_version="risk-features-1",
            features=[FeatureInput(name="claimedAmount", value=1.0)],
        )
    with pytest.raises(ValueError):
        request(CLEAN | {"claimedAmount": float("nan")})
