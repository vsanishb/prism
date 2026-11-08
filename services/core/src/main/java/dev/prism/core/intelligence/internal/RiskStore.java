package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.RiskScreening.AnomalyResult;
import dev.prism.core.aiclient.RiskScreening.DetectorResult;
import dev.prism.core.aiclient.RiskScreening.FeatureResult;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.Indicator;
import dev.prism.core.risk.RiskScorer.CategoryScore;
import dev.prism.core.risk.RiskScorer.Score;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Stores risk screenings. Insert-only: the runtime role cannot change or remove a stored result. */
@Repository
class RiskStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RiskStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<UUID> latestConsolidation(UUID claimId) {
        return jdbc.sql("SELECT id FROM core.claim_consolidations WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1")
                .param(claimId)
                .query(UUID.class)
                .optional();
    }

    record Stored(UUID id, String band, int score, String route) {}

    Optional<Stored> find(UUID consolidationId) {
        return jdbc.sql("SELECT id, band, score, route FROM core.risk_assessments WHERE consolidation_id = ?")
                .param(consolidationId)
                .query((row, index) -> new Stored(
                        row.getObject("id", UUID.class), row.getString("band"), row.getInt("score"), row.getString("route")))
                .optional();
    }

    record Versions(String ruleset, String scoring, String features) {}

    UUID save(UUID claimId, UUID consolidationId, Versions versions, Score score, List<Indicator> indicators,
            AnomalyResult anomaly, List<String> rulesEvaluated) {
        UUID assessment = jdbc.sql("""
                        INSERT INTO core.risk_assessments (claim_id, consolidation_id, ruleset_version, scoring_version,
                            feature_version, model_version, model_fingerprint, baseline_size, score, band, route,
                            rules_only_score, rules_only_band, rules_evaluated, features)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                        """)
                .params(claimId, consolidationId, versions.ruleset(), versions.scoring(), versions.features(),
                        anomaly.modelVersion(), anomaly.fingerprint(), anomaly.baselineSize(), score.total(),
                        score.band().name(), score.route().name(), score.rulesOnly(), score.rulesOnlyBand().name(),
                        rulesEvaluated.toArray(String[]::new), featuresJson(anomaly.features()))
                .query(UUID.class)
                .single();
        for (CategoryScore category : score.categories()) {
            jdbc.sql("""
                            INSERT INTO core.risk_category_scores (assessment_id, category, points, cap, contribution)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                    .params(assessment, category.category().name(), category.points(), category.cap(), category.contribution())
                    .update();
        }
        for (int index = 0; index < indicators.size(); index++) {
            Indicator indicator = indicators.get(index);
            UUID indicatorId = jdbc.sql("""
                            INSERT INTO core.risk_indicators (assessment_id, position, rule_id, rule_version, category, kind,
                                severity, points, title, explanation)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                            """)
                    .params(assessment, index + 1, indicator.ruleId(), indicator.ruleVersion(), indicator.category().name(),
                            indicator.kind().name(), indicator.severity().name(), score.indicatorPoints().get(index),
                            indicator.title(), indicator.explanation())
                    .query(UUID.class)
                    .single();
            int position = 0;
            for (Evidence evidence : indicator.evidence()) {
                jdbc.sql("""
                                INSERT INTO core.risk_evidence (indicator_id, position, label, detail, document_id, page, fact_id,
                                    line_item_id, matched_claim_id)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .params(indicatorId, ++position, evidence.label(), evidence.detail(), evidence.documentId(),
                                evidence.page(), evidence.factId(), evidence.lineItemId(), evidence.matchedClaimId())
                        .update();
            }
        }
        for (DetectorResult detector : anomaly.detectors()) {
            jdbc.sql("""
                            INSERT INTO core.risk_anomaly_results (assessment_id, detector, score, threshold, percentile,
                                flagged, points)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(assessment, detector.detector(), decimal(detector.score(), 6), decimal(detector.threshold(), 6),
                            decimal(detector.percentile(), 2), detector.flagged(), score.detectorPoints().get(detector.detector()))
                    .update();
        }
        return assessment;
    }

    /** A list, not an object, so that the detectors' feature order survives jsonb. */
    private String featuresJson(List<FeatureResult> features) {
        return json.writeValueAsString(features);
    }

    private static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    boolean hasSummary(UUID assessmentId) {
        return jdbc.sql("SELECT count(*) > 0 FROM core.risk_summaries WHERE assessment_id = ?")
                .param(assessmentId)
                .query(Boolean.class)
                .single();
    }

    /** One signal as the summary sees it: a stored indicator or a flagged detector. */
    record StoredSignal(String ref, String source, String kind, String category, String severity, String title,
            String explanation, int points) {}

    /** Indicators by position, then flagged detectors: the order the summary's IDs follow. */
    List<StoredSignal> signals(UUID assessmentId) {
        List<StoredSignal> signals = new java.util.ArrayList<>(jdbc.sql("""
                        SELECT position, category, kind, severity, title, explanation, points FROM core.risk_indicators
                        WHERE assessment_id = ? ORDER BY position
                        """)
                .param(assessmentId)
                .query((row, index) -> new StoredSignal("indicator:" + row.getInt("position"), "RULE", row.getString("kind"),
                        row.getString("category"), row.getString("severity"), row.getString("title"),
                        row.getString("explanation"), row.getInt("points")))
                .list());
        signals.addAll(jdbc.sql("""
                        SELECT detector, percentile, points FROM core.risk_anomaly_results
                        WHERE assessment_id = ? AND flagged ORDER BY detector
                        """)
                .param(assessmentId)
                .query((row, index) -> {
                    String detector = row.getString("detector");
                    return new StoredSignal("detector:" + detector, detector, "STATISTICAL_ANOMALY", "STATISTICAL", null,
                            RiskSignals.detectorTitle(detector),
                            RiskSignals.detectorExplanation(detector, row.getBigDecimal("percentile")),
                            row.getInt("points"));
                })
                .list());
        return signals;
    }

    void saveSummary(UUID assessmentId, String status, String text, List<String> signalRefs, List<String> cited,
            String problem, String promptVersion) {
        jdbc.sql("""
                        INSERT INTO core.risk_summaries (assessment_id, status, text, signal_refs, cited, problem, prompt_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (assessment_id) DO NOTHING
                        """)
                .params(assessmentId, status, text, signalRefs.toArray(String[]::new), cited.toArray(String[]::new), problem,
                        promptVersion)
                .update();
    }
}
