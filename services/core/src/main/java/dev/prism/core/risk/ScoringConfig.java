package dev.prism.core.risk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned scoring weights (D-085). They are judgment, not learned from outcomes, and every result
 * stores the version it used. Loaded and checked at startup, so a bad file stops the service.
 */
public record ScoringConfig(
        String version,
        Map<Severity, Integer> severityPoints,
        Map<Category, Integer> categoryCaps,
        Map<String, Integer> detectorPoints,
        int mediumFrom,
        int highFrom,
        int maximum) {

    public static final String RESOURCE = "/risk/risk-scoring-1.json";
    static final Set<String> DETECTORS = Set.of("ISOLATION_FOREST", "LOCAL_OUTLIER_FACTOR");

    public static ScoringConfig load() {
        try (InputStream stream = ScoringConfig.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Risk scoring configuration is missing: " + RESOURCE);
            }
            ScoringConfig config = JsonMapper.builder().build().readValue(stream, ScoringConfig.class);
            config.validate();
            return config;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    void validate() {
        require(version != null && !version.isBlank(), "a version");
        require(severityPoints != null && severityPoints.keySet().equals(EnumSet.allOf(Severity.class))
                && severityPoints.values().stream().allMatch(points -> points > 0), "positive points for every severity");
        require(categoryCaps != null && categoryCaps.keySet().equals(EnumSet.allOf(Category.class))
                && categoryCaps.values().stream().allMatch(cap -> cap >= 0), "a cap for every category");
        require(detectorPoints != null && detectorPoints.keySet().equals(DETECTORS)
                && detectorPoints.values().stream().allMatch(points -> points > 0), "positive points for both detectors");
        require(0 < mediumFrom && mediumFrom < highFrom && highFrom <= maximum && maximum <= 100, "ordered band thresholds");
        // The property D-085 promises: statistical anomalies alone can never place a claim in HIGH.
        require(categoryCaps.get(Category.STATISTICAL) < highFrom, "a statistical cap below the HIGH threshold");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Risk scoring configuration needs " + what);
        }
    }
}
