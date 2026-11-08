package dev.prism.core.aiclient;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import java.util.List;
import java.util.UUID;

/**
 * The AI service's risk contracts (D-082, D-086). Field names match its JSON. Requests carry numbers
 * and de-identified signal text only: never names, hospitals, or policy numbers.
 */
public final class RiskScreening {

    private RiskScreening() {}

    /** @param value null when the claim's facts cannot give it */
    public record FeatureValue(String name, Double value) {}

    public record AnomalyRequest(UUID claimId, String featureVersion, List<FeatureValue> features) {}

    public record DetectorResult(String detector, double score, double threshold, double percentile, boolean flagged) {}

    public record FeatureResult(String name, double value, boolean imputed, double baselineMedian, double baselinePercentile) {}

    public record AnomalyResult(
            UUID claimId,
            String modelVersion,
            String featureVersion,
            String generatorVersion,
            String fingerprint,
            int baselineSize,
            List<DetectorResult> detectors,
            List<FeatureResult> features) {}

    /** @param severity null for a statistical signal */
    public record Signal(
            String id,
            String source,
            String kind,
            String category,
            String severity,
            String title,
            String explanation,
            int contribution) {}

    public record SummaryRequest(UUID claimId, String band, int score, String route, List<Signal> signals) {}

    public record SummaryResult(
            UUID claimId,
            String status,
            String text,
            List<String> cited,
            String problem,
            String promptVersion,
            List<ModelRun> runs) {}
}
