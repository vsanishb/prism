package dev.prism.core.intelligence.internal;

import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.risk.RiskScorer;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.RuleEngine;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The latest risk screening of a claim, with its evidence, for reviewers. Signals, never findings of fraud. */
@Service
class RiskReader {

    private final ClaimDirectory claims;
    private final RiskScorer scorer;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    RiskReader(ClaimDirectory claims, RiskScorer scorer, JdbcClient jdbc, JsonMapper json) {
        this.claims = claims;
        this.scorer = scorer;
        this.jdbc = jdbc;
        this.json = json;
    }

    record RiskResponse(RiskView screening) {}

    /**
     * @param current whether it reflects the claim's latest facts; a newer screening may be pending
     * @param rulesOnlyScore what the rules alone gave, to show what the detectors added
     */
    record RiskView(
            UUID id,
            Instant createdAt,
            boolean current,
            int score,
            String band,
            String route,
            int rulesOnlyScore,
            String rulesOnlyBand,
            Thresholds thresholds,
            Versions versions,
            List<CategoryView> categories,
            List<IndicatorView> indicators,
            List<DetectorView> detectors,
            List<FeatureView> features,
            List<RuleView> rules,
            SummaryView summary) {}

    record Thresholds(int mediumFrom, int highFrom, int maximum) {}

    record Versions(String ruleset, String scoring, String features, String model, String fingerprint, int baselineSize) {}

    record CategoryView(String category, int points, int cap, int contribution) {}

    record IndicatorView(
            int position,
            String signalId,
            String ruleId,
            int ruleVersion,
            String category,
            String kind,
            String severity,
            int points,
            String title,
            String explanation,
            List<EvidenceView> evidence) {}

    /** @param region where on the page, as fractions from the top left, when the page has a text layer */
    record EvidenceView(String label, String detail, UUID documentId, Integer page, List<BigDecimal> region, UUID factId,
            UUID lineItemId, MatchedClaim matchedClaim) {}

    /** Another claim, by number only. Opening it applies that claim's own access rules. */
    record MatchedClaim(UUID id, String claimNumber) {}

    record DetectorView(String detector, String signalId, BigDecimal score, BigDecimal threshold, BigDecimal percentile,
            boolean flagged, int points) {}

    record FeatureView(String name, double value, boolean imputed, double baselineMedian, double baselinePercentile) {}

    record RuleView(String id, int version, String category, String kind, String severity, String title, String checks,
            boolean triggered) {}

    /** @param signals what each cited ID stands for: "indicator:3" or "detector:ISOLATION_FOREST" */
    record SummaryView(String status, String text, List<String> cited, Map<String, String> signals, String problem,
            String promptVersion) {}

    private record Header(UUID id, UUID consolidationId, Instant createdAt, int score, String band, String route,
            int rulesOnlyScore, String rulesOnlyBand, Versions versions, List<String> evaluated, String features) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public RiskResponse latest(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        Optional<Header> found = jdbc.sql("SELECT * FROM core.risk_assessments WHERE claim_id = ? ORDER BY id DESC LIMIT 1")
                .param(claim.id())
                .query((row, index) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getObject("consolidation_id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getInt("score"),
                        row.getString("band"),
                        row.getString("route"),
                        row.getInt("rules_only_score"),
                        row.getString("rules_only_band"),
                        new Versions(row.getString("ruleset_version"), row.getString("scoring_version"),
                                row.getString("feature_version"), row.getString("model_version"),
                                row.getString("model_fingerprint"), row.getInt("baseline_size")),
                        texts(row.getArray("rules_evaluated")),
                        row.getString("features")))
                .optional();
        if (found.isEmpty()) {
            return new RiskResponse(null);
        }
        Header header = found.get();
        UUID latestConsolidation = jdbc.sql(
                        "SELECT id FROM core.claim_consolidations WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1")
                .param(claim.id())
                .query(UUID.class)
                .single();

        Optional<SummaryView> summary = summary(header.id());
        Map<String, String> signalIds = new LinkedHashMap<>();
        summary.ifPresent(view -> view.signals().forEach((id, ref) -> signalIds.put(ref, id)));

        List<IndicatorView> indicators = indicators(header.id(), signalIds);
        Set<String> triggered = new java.util.HashSet<>();
        indicators.forEach(indicator -> triggered.add(indicator.ruleId()));
        List<RuleView> rules = RuleEngine.rules().stream()
                .filter(rule -> header.evaluated().contains(rule.id()))
                .map(rule -> rule(rule, triggered.contains(rule.id())))
                .toList();

        return new RiskResponse(new RiskView(
                header.id(),
                header.createdAt(),
                header.consolidationId().equals(latestConsolidation),
                header.score(),
                header.band(),
                header.route(),
                header.rulesOnlyScore(),
                header.rulesOnlyBand(),
                new Thresholds(scorer.config().mediumFrom(), scorer.config().highFrom(), scorer.config().maximum()),
                header.versions(),
                categories(header.id()),
                indicators,
                detectors(header.id(), signalIds),
                json.readValue(header.features(), new TypeReference<List<FeatureView>>() {}),
                rules,
                summary.orElse(null)));
    }

    private static RuleView rule(RuleDescription rule, boolean triggered) {
        return new RuleView(rule.id(), rule.version(), rule.category().name(), rule.kind().name(), rule.severity().name(),
                rule.title(), rule.checks(), triggered);
    }

    private List<CategoryView> categories(UUID assessmentId) {
        return jdbc.sql("""
                        SELECT category, points, cap, contribution FROM core.risk_category_scores WHERE assessment_id = ?
                        ORDER BY array_position(ARRAY['CONSISTENCY', 'FINANCIAL', 'DUPLICATE', 'DOCUMENT', 'MISSING_INFORMATION',
                            'STATISTICAL'], category)
                        """)
                .param(assessmentId)
                .query((row, index) -> new CategoryView(row.getString("category"), row.getInt("points"), row.getInt("cap"),
                        row.getInt("contribution")))
                .list();
    }

    private List<IndicatorView> indicators(UUID assessmentId, Map<String, String> signalIds) {
        Map<UUID, List<EvidenceView>> evidence = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT e.*, c.claim_number, coalesce(li.region, source.region) AS region
                        FROM core.risk_evidence e
                        JOIN core.risk_indicators i ON i.id = e.indicator_id
                        LEFT JOIN core.claims c ON c.id = e.matched_claim_id
                        LEFT JOIN core.claim_line_items li ON li.id = e.line_item_id
                        LEFT JOIN LATERAL (
                            SELECT s.region FROM core.claim_fact_sources s
                            WHERE s.fact_id = e.fact_id AND s.document_id = e.document_id AND s.page = e.page
                            ORDER BY s.id LIMIT 1
                        ) source ON true
                        WHERE i.assessment_id = ? ORDER BY e.indicator_id, e.position
                        """)
                .param(assessmentId)
                .query((row, index) -> {
                    UUID matched = row.getObject("matched_claim_id", UUID.class);
                    evidence.computeIfAbsent(row.getObject("indicator_id", UUID.class), key -> new ArrayList<>()).add(new EvidenceView(
                            row.getString("label"),
                            row.getString("detail"),
                            row.getObject("document_id", UUID.class),
                            (Integer) row.getObject("page"),
                            decimals(row.getArray("region")),
                            row.getObject("fact_id", UUID.class),
                            row.getObject("line_item_id", UUID.class),
                            matched == null ? null : new MatchedClaim(matched, row.getString("claim_number"))));
                    return null;
                })
                .list();
        return jdbc.sql("SELECT * FROM core.risk_indicators WHERE assessment_id = ? ORDER BY position")
                .param(assessmentId)
                .query((row, index) -> {
                    int position = row.getInt("position");
                    return new IndicatorView(
                            position,
                            signalIds.get("indicator:" + position),
                            row.getString("rule_id"),
                            row.getInt("rule_version"),
                            row.getString("category"),
                            row.getString("kind"),
                            row.getString("severity"),
                            row.getInt("points"),
                            row.getString("title"),
                            row.getString("explanation"),
                            List.copyOf(evidence.getOrDefault(row.getObject("id", UUID.class), List.of())));
                })
                .list();
    }

    private List<DetectorView> detectors(UUID assessmentId, Map<String, String> signalIds) {
        return jdbc.sql("SELECT * FROM core.risk_anomaly_results WHERE assessment_id = ? ORDER BY detector")
                .param(assessmentId)
                .query((row, index) -> new DetectorView(
                        row.getString("detector"),
                        signalIds.get("detector:" + row.getString("detector")),
                        row.getBigDecimal("score"),
                        row.getBigDecimal("threshold"),
                        row.getBigDecimal("percentile"),
                        row.getBoolean("flagged"),
                        row.getInt("points")))
                .list();
    }

    private Optional<SummaryView> summary(UUID assessmentId) {
        return jdbc.sql("SELECT * FROM core.risk_summaries WHERE assessment_id = ?")
                .param(assessmentId)
                .query((row, index) -> {
                    List<String> refs = texts(row.getArray("signal_refs"));
                    Map<String, String> signals = new LinkedHashMap<>();
                    for (int position = 0; position < refs.size(); position++) {
                        signals.put("S" + (position + 1), refs.get(position));
                    }
                    return new SummaryView(row.getString("status"), row.getString("text"), texts(row.getArray("cited")), signals,
                            row.getString("problem"), row.getString("prompt_version"));
                })
                .optional();
    }

    private static List<BigDecimal> decimals(Array array) throws SQLException {
        return array == null ? null : List.of((BigDecimal[]) array.getArray());
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
