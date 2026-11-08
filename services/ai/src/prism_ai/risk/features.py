"""The claim features the detectors see. Core computes them from facts it holds; this module only
transforms them for the models. Every feature is something Prism derives for a real claim, so the
synthetic baseline never relies on information the system cannot produce."""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

FEATURE_VERSION = "risk-features-1"


class Transform(StrEnum):
    NONE = "NONE"
    LOG = "LOG"


@dataclass(frozen=True)
class FeatureSpec:
    name: str
    label: str
    transform: Transform
    # Bounds keep one absurd value from dominating the scaled space. They are wide: only nonsense is clipped.
    low: float
    high: float


FEATURES: tuple[FeatureSpec, ...] = (
    FeatureSpec("claimedAmount", "Claimed amount", Transform.LOG, 0, 1e8),
    FeatureSpec("sumInsuredRatio", "Claimed amount relative to the sum insured", Transform.NONE, 0, 5),
    FeatureSpec("lengthOfStayDays", "Length of stay, in nights", Transform.NONE, 0, 90),
    FeatureSpec("amountPerDay", "Claimed amount per day of stay", Transform.LOG, 0, 1e8),
    FeatureSpec("lineItemCount", "Bill line items read", Transform.NONE, 0, 200),
    FeatureSpec("largestItemShare", "Largest line item's share of the bills", Transform.NONE, 0, 1),
    FeatureSpec("billDeviation", "Claimed amount above or below the bills", Transform.NONE, -1, 5),
    FeatureSpec("priorClaims12m", "The patient's earlier claims in the past year", Transform.NONE, 0, 50),
    FeatureSpec("daysSinceCoverStart", "Days from cover start to admission", Transform.LOG, 0, 20000),
)
FEATURE_NAMES: tuple[str, ...] = tuple(spec.name for spec in FEATURES)


def transform(raw: NDArray[np.float64]) -> NDArray[np.float64]:
    """Rows of raw feature values, in `FEATURES` order, to the space the detectors are fitted in."""
    columns = []
    for index, spec in enumerate(FEATURES):
        column = np.clip(raw[:, index], spec.low, spec.high)
        columns.append(np.log1p(column) if spec.transform is Transform.LOG else column)
    return np.column_stack(columns).astype(np.float64)
