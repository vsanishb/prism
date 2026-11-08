package dev.prism.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prism.core.risk.RiskScorer.CategoryScore;
import dev.prism.core.risk.RiskScorer.DetectorOutcome;
import dev.prism.core.risk.RiskScorer.Score;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Scoring is deterministic configuration applied to signals (D-085). These pin its promises. */
class RiskScorerTest {

    private final RiskScorer scorer = new RiskScorer(ScoringConfig.load());

    private static Indicator indicator(Category category, Severity severity) {
        return new Indicator("T-01", 1, category, IndicatorKind.RISK_INDICATOR, severity, "Title", "Explanation.", List.of());
    }

    private static List<DetectorOutcome> detectors(boolean forest, boolean lof) {
        return List.of(new DetectorOutcome("ISOLATION_FOREST", forest), new DetectorOutcome("LOCAL_OUTLIER_FACTOR", lof));
    }

    private static int contribution(Score score, Category category) {
        return score.categories().stream().filter(found -> found.category() == category).findFirst().orElseThrow().contribution();
    }

    @Test
    void theVersionedConfigurationLoadsAndHoldsItsPromise() {
        ScoringConfig config = scorer.config();
        assertThat(config.version()).isEqualTo("risk-scoring-1");
        assertThat(config.categoryCaps().get(Category.STATISTICAL)).isLessThan(config.highFrom());
    }

    @Test
    void aClaimWithNoSignalsIsLowAndRoutedNormally() {
        Score score = scorer.score(List.of(), detectors(false, false));
        assertThat(score.total()).isZero();
        assertThat(score.band()).isEqualTo(Band.LOW);
        assertThat(score.route()).isEqualTo(ReviewRoute.STANDARD_REVIEW);
    }

    @Test
    void severityPointsAndBandBoundaries() {
        assertThat(scorer.score(List.of(indicator(Category.FINANCIAL, Severity.LOW)), detectors(false, false)).total()).isEqualTo(5);
        assertThat(scorer.score(List.of(indicator(Category.FINANCIAL, Severity.MEDIUM)), detectors(false, false)).total()).isEqualTo(12);
        assertThat(scorer.score(List.of(indicator(Category.FINANCIAL, Severity.HIGH)), detectors(false, false)).total()).isEqualTo(25);
        assertThat(scorer.band(19)).isEqualTo(Band.LOW);
        assertThat(scorer.band(20)).isEqualTo(Band.MEDIUM);
        assertThat(scorer.band(44)).isEqualTo(Band.MEDIUM);
        assertThat(scorer.band(45)).isEqualTo(Band.HIGH);
    }

    @Test
    void aNoisyCategoryIsCapped() {
        List<Indicator> many = new ArrayList<>();
        for (int count = 0; count < 6; count++) {
            many.add(indicator(Category.DOCUMENT, Severity.MEDIUM));
        }
        Score score = scorer.score(many, detectors(false, false));
        CategoryScore document = score.categories().stream().filter(found -> found.category() == Category.DOCUMENT).findFirst().orElseThrow();
        assertThat(document.points()).isEqualTo(72);
        assertThat(document.contribution()).isEqualTo(10);
        assertThat(score.band()).isEqualTo(Band.LOW);
    }

    @Test
    void anomaliesAloneCanNeverMakeAClaimHighRisk() {
        Score score = scorer.score(List.of(), detectors(true, true));
        assertThat(score.total()).isEqualTo(20);
        assertThat(score.band()).isEqualTo(Band.MEDIUM);
        assertThat(score.rulesOnly()).isZero();
        assertThat(score.rulesOnlyBand()).isEqualTo(Band.LOW);
    }

    @Test
    void anomaliesCanRaiseAClaimThatRulesAlreadyFlagged() {
        // The disguised demonstration: a near-identical bill and a similar earlier claim.
        List<Indicator> rules = List.of(indicator(Category.DUPLICATE, Severity.HIGH), indicator(Category.DUPLICATE, Severity.MEDIUM));
        Score score = scorer.score(rules, detectors(true, true));
        assertThat(score.rulesOnly()).isEqualTo(37);
        assertThat(score.rulesOnlyBand()).isEqualTo(Band.MEDIUM);
        assertThat(score.total()).isEqualTo(57);
        assertThat(score.band()).isEqualTo(Band.HIGH);
        assertThat(score.route()).isEqualTo(ReviewRoute.SENIOR_REVIEW);
        assertThat(score.detectorPoints()).containsEntry("ISOLATION_FOREST", 10).containsEntry("LOCAL_OUTLIER_FACTOR", 10);
    }

    @Test
    void theTotalIsCappedAndEveryContributionIsKept() {
        List<Indicator> rules = new ArrayList<>();
        for (Category category : List.of(Category.CONSISTENCY, Category.FINANCIAL, Category.DUPLICATE)) {
            for (int count = 0; count < 3; count++) {
                rules.add(indicator(category, Severity.HIGH));
            }
        }
        Score score = scorer.score(rules, detectors(true, true));
        assertThat(score.total()).isEqualTo(100);
        assertThat(contribution(score, Category.CONSISTENCY)).isEqualTo(40);
        assertThat(contribution(score, Category.DUPLICATE)).isEqualTo(50);
        assertThat(contribution(score, Category.STATISTICAL)).isEqualTo(20);
        assertThat(score.indicatorPoints()).hasSize(9).containsOnly(25);
    }

    @Test
    void scoringIsDeterministic() {
        List<Indicator> rules = List.of(indicator(Category.CONSISTENCY, Severity.HIGH), indicator(Category.FINANCIAL, Severity.LOW));
        assertThat(scorer.score(rules, detectors(true, false))).isEqualTo(scorer.score(rules, detectors(true, false)));
    }

    @Test
    void aConfigurationThatLetsAnomaliesAloneReachHighIsRefused() {
        ScoringConfig loaded = ScoringConfig.load();
        Map<Category, Integer> caps = new EnumMap<>(loaded.categoryCaps());
        caps.put(Category.STATISTICAL, loaded.highFrom());
        ScoringConfig unsafe = new ScoringConfig(loaded.version(), loaded.severityPoints(), caps, loaded.detectorPoints(),
                loaded.mediumFrom(), loaded.highFrom(), loaded.maximum());
        assertThatThrownBy(() -> new RiskScorer(unsafe)).hasMessageContaining("statistical cap below the HIGH threshold");
    }

    @Test
    void aConfigurationMissingASeverityIsRefused() {
        ScoringConfig loaded = ScoringConfig.load();
        Map<Severity, Integer> points = new EnumMap<>(loaded.severityPoints());
        points.remove(Severity.LOW);
        ScoringConfig broken = new ScoringConfig(loaded.version(), points, loaded.categoryCaps(), loaded.detectorPoints(),
                loaded.mediumFrom(), loaded.highFrom(), loaded.maximum());
        assertThatThrownBy(() -> new RiskScorer(broken)).hasMessageContaining("every severity");
    }
}
