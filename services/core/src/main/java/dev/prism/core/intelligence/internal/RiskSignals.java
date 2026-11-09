package dev.prism.core.intelligence.internal;

import java.math.BigDecimal;

/** How a detector's result reads as a signal. Unusual is all it can say: never suspicious, never fraud. */
final class RiskSignals {

    private RiskSignals() {}

    static String detectorTitle(String detector) {
        return switch (detector) {
            case "ISOLATION_FOREST" -> "Statistically unusual compared with all baseline claims";
            case "LOCAL_OUTLIER_FACTOR" -> "Statistically unusual compared with similar claims";
            default -> throw new IllegalArgumentException("Unknown detector " + detector);
        };
    }

    static String detectorExplanation(String detector, BigDecimal percentile) {
        String method = "ISOLATION_FOREST".equals(detector) ? "Isolation Forest" : "Local Outlier Factor";
        return "%s scores this claim as more unusual than %s%% of the baseline claims.".formatted(
                method, percentile.stripTrailingZeros().toPlainString());
    }
}
