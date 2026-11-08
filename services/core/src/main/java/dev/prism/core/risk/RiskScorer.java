package dev.prism.core.risk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines rule indicators and detector results into a score, a band, and a review route (D-085).
 * Every contribution is kept, so a reviewer can see exactly how the score was built. The score is a
 * screening measure: it is not a probability, and no band changes a claim's outcome.
 */
public final class RiskScorer {

    private final ScoringConfig config;

    public RiskScorer(ScoringConfig config) {
        config.validate();
        this.config = config;
    }

    public ScoringConfig config() {
        return config;
    }

    /** @param flagged whether this detector found the claim beyond its baseline threshold */
    public record DetectorOutcome(String detector, boolean flagged) {}

    public record CategoryScore(Category category, int points, int cap, int contribution) {}

    /**
     * @param indicatorPoints points for each indicator, in the order given
     * @param detectorPoints points for each detector, zero when it did not flag
     * @param rulesOnly the score the rules alone would give, to show what the detectors added
     */
    public record Score(
            int total,
            Band band,
            ReviewRoute route,
            List<CategoryScore> categories,
            List<Integer> indicatorPoints,
            Map<String, Integer> detectorPoints,
            int rulesOnly,
            Band rulesOnlyBand) {}

    public Score score(List<Indicator> indicators, List<DetectorOutcome> detectors) {
        Map<Category, Integer> points = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            points.put(category, 0);
        }
        List<Integer> indicatorPoints = new ArrayList<>();
        for (Indicator indicator : indicators) {
            int value = config.severityPoints().get(indicator.severity());
            indicatorPoints.add(value);
            points.merge(indicator.category(), value, Integer::sum);
        }
        Map<String, Integer> detectorPoints = new LinkedHashMap<>();
        for (DetectorOutcome detector : detectors) {
            int value = detector.flagged() ? config.detectorPoints().get(detector.detector()) : 0;
            detectorPoints.put(detector.detector(), value);
            points.merge(Category.STATISTICAL, value, Integer::sum);
        }

        List<CategoryScore> categories = new ArrayList<>();
        int sum = 0;
        int rulesSum = 0;
        for (Category category : Category.values()) {
            int cap = config.categoryCaps().get(category);
            int contribution = Math.min(points.get(category), cap);
            categories.add(new CategoryScore(category, points.get(category), cap, contribution));
            sum += contribution;
            if (category != Category.STATISTICAL) {
                rulesSum += contribution;
            }
        }
        int total = Math.min(sum, config.maximum());
        int rulesOnly = Math.min(rulesSum, config.maximum());
        Band band = band(total);
        return new Score(
                total,
                band,
                band == Band.HIGH ? ReviewRoute.SENIOR_REVIEW : ReviewRoute.STANDARD_REVIEW,
                List.copyOf(categories),
                List.copyOf(indicatorPoints),
                Map.copyOf(detectorPoints),
                rulesOnly,
                band(rulesOnly));
    }

    Band band(int score) {
        if (score >= config.highFrom()) {
            return Band.HIGH;
        }
        return score >= config.mediumFrom() ? Band.MEDIUM : Band.LOW;
    }
}
