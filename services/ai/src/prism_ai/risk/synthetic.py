"""A reproducible synthetic population of Indian health insurance claims, for fitting and evaluating
the anomaly detectors. **Entirely synthetic**: the distributions are judgment about plausible claims,
not measurements of real ones, and nothing here describes any real insurer, hospital, or person.

Three sets, each from its own seed, so that none overlaps another:
- the baseline, unlabeled, which the detectors are fitted on;
- held-out normal claims, for false-positive rates;
- controlled anomalies, labeled by type, used only to evaluate. Labels never reach training (D-083).
"""

from dataclasses import dataclass
from enum import StrEnum

import numpy as np
from numpy.typing import NDArray

from prism_ai.risk.features import FEATURES

GENERATOR_VERSION = "synthetic-claims-1"
BASELINE_SEED = 7301
HELD_OUT_SEED = 7302
ANOMALY_SEED = 7303
BASELINE_SIZE = 5000
HELD_OUT_SIZE = 2000
ANOMALIES_PER_TYPE = 30

SUMS_INSURED = np.array([300_000.0, 500_000.0, 1_000_000.0, 1_500_000.0, 2_500_000.0])
SUM_INSURED_WEIGHTS = np.array([0.25, 0.40, 0.25, 0.07, 0.03])


@dataclass(frozen=True)
class Profile:
    """A kind of admission. Costs are bill totals in INR, before any amount is left out of the claim."""

    name: str
    weight: float
    nights: tuple[int, int]
    per_night: float
    fixed: float
    cost_spread: float
    items: tuple[int, int]
    largest_share: tuple[float, float]


PROFILES: tuple[Profile, ...] = (
    Profile("medical_short", 0.35, (1, 4), 14_000, 8_000, 0.35, (6, 10), (0.22, 0.45)),
    Profile("medical_long", 0.15, (4, 9), 12_000, 15_000, 0.30, (8, 14), (0.20, 0.40)),
    Profile("surgical_minor", 0.20, (1, 4), 7_000, 80_000, 0.30, (6, 11), (0.35, 0.55)),
    Profile("surgical_major", 0.08, (4, 10), 10_000, 280_000, 0.25, (9, 16), (0.35, 0.60)),
    Profile("maternity", 0.10, (2, 4), 6_000, 52_000, 0.25, (6, 9), (0.30, 0.50)),
    Profile("day_care", 0.12, (0, 0), 0, 40_000, 0.35, (3, 6), (0.45, 0.70)),
)


class AnomalyType(StrEnum):
    INFLATED_CLAIM = "INFLATED_CLAIM"
    SHORT_STAY_HIGH_COST = "SHORT_STAY_HIGH_COST"
    FREQUENT_CLAIMANT = "FREQUENT_CLAIMANT"
    EARLY_LARGE_CLAIM = "EARLY_LARGE_CLAIM"
    CONCENTRATED_BILL = "CONCENTRATED_BILL"
    ABOVE_SUM_INSURED = "ABOVE_SUM_INSURED"
    SUBTLE_COMBINATION = "SUBTLE_COMBINATION"


ANOMALY_DESCRIPTIONS: dict[AnomalyType, str] = {
    AnomalyType.INFLATED_CLAIM: "Claimed 25% to 150% more than the bills",
    AnomalyType.SHORT_STAY_HIGH_COST: "Up to two nights, with a surgical-scale bill",
    AnomalyType.FREQUENT_CLAIMANT: "Three to six earlier claims in the past year, otherwise ordinary",
    AnomalyType.EARLY_LARGE_CLAIM: "Within 25 days of cover starting, for most of the sum insured",
    AnomalyType.CONCENTRATED_BILL: "Many line items, one of them almost the whole bill",
    AnomalyType.ABOVE_SUM_INSURED: "A medical admission claiming well above the sum insured",
    AnomalyType.SUBTLE_COMBINATION: (
        "Each value plausible alone: a short stay, most of the sum insured, one dominant item, "
        "and earlier claims"
    ),
}


@dataclass
class Claims:
    """Raw feature rows, in `FEATURES` order, with the values they were derived from."""

    bills: NDArray[np.float64]
    claimed: NDArray[np.float64]
    sum_insured: NDArray[np.float64]
    nights: NDArray[np.float64]
    items: NDArray[np.float64]
    largest_share: NDArray[np.float64]
    prior_claims: NDArray[np.float64]
    days_since_cover: NDArray[np.float64]

    def features(self) -> NDArray[np.float64]:
        columns = {
            "claimedAmount": self.claimed,
            "sumInsuredRatio": self.claimed / self.sum_insured,
            "lengthOfStayDays": self.nights,
            "amountPerDay": self.claimed / np.maximum(self.nights, 1),
            "lineItemCount": self.items,
            "largestItemShare": self.largest_share,
            "billDeviation": self.claimed / self.bills - 1,
            "priorClaims12m": self.prior_claims,
            "daysSinceCoverStart": self.days_since_cover,
        }
        return np.column_stack([columns[spec.name] for spec in FEATURES]).astype(np.float64)


def normal_claims(size: int, seed: int) -> Claims:
    rng = np.random.default_rng(seed)
    weights = np.array([profile.weight for profile in PROFILES])
    chosen = rng.choice(len(PROFILES), size=size, p=weights / weights.sum())
    bills = np.empty(size)
    nights = np.empty(size)
    items = np.empty(size)
    share = np.empty(size)
    for index, profile in enumerate(PROFILES):
        rows = np.flatnonzero(chosen == index)
        count = rows.size
        stay = rng.integers(profile.nights[0], profile.nights[1] + 1, size=count).astype(np.float64)
        median = profile.fixed + profile.per_night * stay
        bills[rows] = np.round(median * rng.lognormal(0.0, profile.cost_spread, size=count), -1)
        nights[rows] = stay
        items[rows] = rng.integers(profile.items[0], profile.items[1] + 1, size=count)
        low, high = profile.largest_share
        share[rows] = low + (high - low) * rng.beta(2.0, 2.0, size=count)

    # Most claims ask for the whole bill. Some leave out non-payable items, so ask for less.
    reduced = rng.random(size) < 0.2
    claimed = np.round(np.where(reduced, bills * (1 - rng.uniform(0.02, 0.15, size)), bills), 2)
    sum_insured = rng.choice(SUMS_INSURED, size=size, p=SUM_INSURED_WEIGHTS)
    prior = rng.poisson(0.2, size=size).astype(np.float64)
    # Most cover has run for a while. Early claims are rare, because of the initial waiting period.
    phase = rng.random(size)
    days = np.where(
        phase < 0.02,
        rng.integers(1, 31, size),
        np.where(phase < 0.17, rng.integers(31, 366, size), rng.integers(60, 1500, size)),
    ).astype(np.float64)
    return Claims(bills, claimed, sum_insured, nights, items, share, prior, days)


def baseline() -> Claims:
    return normal_claims(BASELINE_SIZE, BASELINE_SEED)


def held_out() -> Claims:
    return normal_claims(HELD_OUT_SIZE, HELD_OUT_SEED)


def anomalies() -> tuple[NDArray[np.float64], list[AnomalyType]]:
    """Normal claims, each altered in one controlled way. Returns feature rows and their types."""
    rng = np.random.default_rng(ANOMALY_SEED)
    rows: list[NDArray[np.float64]] = []
    labels: list[AnomalyType] = []
    for offset, kind in enumerate(AnomalyType):
        claims = normal_claims(ANOMALIES_PER_TYPE, ANOMALY_SEED + 100 + offset)
        _alter(claims, kind, rng)
        rows.append(claims.features())
        labels.extend([kind] * ANOMALIES_PER_TYPE)
    return np.vstack(rows), labels


def _alter(claims: Claims, kind: AnomalyType, rng: np.random.Generator) -> None:
    size = claims.claimed.size
    match kind:
        case AnomalyType.INFLATED_CLAIM:
            claims.claimed = np.round(claims.bills * (1 + rng.uniform(0.25, 1.5, size)), 2)
        case AnomalyType.SHORT_STAY_HIGH_COST:
            claims.nights = rng.integers(0, 3, size).astype(np.float64)
            claims.sum_insured = rng.choice(SUMS_INSURED[1:3], size=size)
            claims.bills = np.round(rng.uniform(180_000, 450_000, size), -1)
            claims.claimed = claims.bills.copy()
            claims.items = rng.integers(4, 9, size).astype(np.float64)
            claims.largest_share = rng.uniform(0.5, 0.75, size)
        case AnomalyType.FREQUENT_CLAIMANT:
            claims.prior_claims = rng.integers(3, 7, size).astype(np.float64)
        case AnomalyType.EARLY_LARGE_CLAIM:
            claims.days_since_cover = rng.integers(1, 26, size).astype(np.float64)
            claims.claimed = np.round(claims.sum_insured * rng.uniform(0.85, 1.0, size), 2)
            claims.bills = claims.claimed.copy()
        case AnomalyType.CONCENTRATED_BILL:
            claims.items = rng.integers(10, 17, size).astype(np.float64)
            claims.largest_share = rng.uniform(0.88, 0.97, size)
        case AnomalyType.ABOVE_SUM_INSURED:
            claims.nights = rng.integers(1, 5, size).astype(np.float64)
            claims.sum_insured = np.full(size, SUMS_INSURED[0])
            claims.claimed = np.round(claims.sum_insured * rng.uniform(1.3, 2.5, size), 2)
            claims.bills = claims.claimed.copy()
        case AnomalyType.SUBTLE_COMBINATION:
            claims.nights = rng.integers(1, 3, size).astype(np.float64)
            claims.sum_insured = np.full(size, SUMS_INSURED[1])
            claims.claimed = np.round(claims.sum_insured * rng.uniform(0.7, 0.9, size), 2)
            claims.bills = claims.claimed.copy()
            claims.items = rng.integers(6, 9, size).astype(np.float64)
            claims.largest_share = rng.uniform(0.5, 0.65, size)
            claims.prior_claims = rng.integers(1, 3, size).astype(np.float64)
            claims.days_since_cover = rng.integers(120, 400, size).astype(np.float64)
