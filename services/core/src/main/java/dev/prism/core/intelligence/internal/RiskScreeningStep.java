package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.RiskScreening;
import dev.prism.core.aiclient.RiskScreening.AnomalyResult;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.ClaimIntelligence.Screening;
import dev.prism.core.intelligence.internal.RiskStore.Stored;
import dev.prism.core.intelligence.internal.RiskStore.StoredSignal;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import dev.prism.core.risk.Indicator;
import dev.prism.core.risk.RiskFeatures;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RiskScorer;
import dev.prism.core.risk.RiskScorer.DetectorOutcome;
import dev.prism.core.risk.RiskScorer.Score;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.RuleEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screens a claim's latest facts for risk (D-082 to D-086): rules here, anomaly detection in the AI
 * service, scoring here, then an optional AI summary. The screening is stored before the summary is
 * asked for, so the summary can never change it. No transaction is held during a call to the AI service.
 */
@Component
class RiskScreeningStep {

    private static final Set<String> DETECTORS = Set.of("ISOLATION_FOREST", "LOCAL_OUTLIER_FACTOR");

    private final ClaimDirectory claims;
    private final PolicyDirectory policies;
    private final RiskInputLoader loader;
    private final RiskStore store;
    private final AssessmentStore runs;
    private final RiskScorer scorer;
    private final AiServiceClient ai;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    RiskScreeningStep(
            ClaimDirectory claims,
            PolicyDirectory policies,
            RiskInputLoader loader,
            RiskStore store,
            AssessmentStore runs,
            RiskScorer scorer,
            AiServiceClient ai,
            AuditRecorder audit,
            TransactionTemplate transactions) {
        this.claims = claims;
        this.policies = policies;
        this.loader = loader;
        this.store = store;
        this.runs = runs;
        this.scorer = scorer;
        this.ai = ai;
        this.audit = audit;
        this.transactions = transactions;
    }

    /** Safe to repeat: a consolidation is screened once, and summarized once. */
    Screening screen(UUID claimId) {
        ClaimRecord claim = claims.get(claimId);
        UUID consolidation = store.latestConsolidation(claimId)
                .orElseThrow(() -> new Failure("no_consolidated_facts", false, List.of()));
        if (claim.closed()) {
            return new Screening(null);
        }
        Stored stored = store.find(consolidation).orElseGet(() -> assess(claim, consolidation));
        if (!store.hasSummary(stored.id())) {
            summarize(claimId, stored);
        }
        return new Screening(stored.band());
    }

    private Stored assess(ClaimRecord claim, UUID consolidation) {
        VersionRef version = policies.findVersion(claim.policyVersionId()).orElseThrow();
        LocalDate coverStart = policies.coverStart(version.id()).orElse(null);
        RiskInput input = loader.load(claim, consolidation, version, coverStart);
        List<Indicator> indicators = RuleEngine.evaluate(input);
        Map<String, Double> features = RiskFeatures.compute(input);

        AnomalyResult anomaly = ai.scoreRiskAnomaly(new RiskScreening.AnomalyRequest(
                claim.id(),
                RiskFeatures.VERSION,
                features.entrySet().stream().map(entry -> new RiskScreening.FeatureValue(entry.getKey(), entry.getValue())).toList()));
        checkAnomaly(claim.id(), anomaly);

        Score score = scorer.score(indicators, anomaly.detectors().stream()
                .map(detector -> new DetectorOutcome(detector.detector(), detector.flagged()))
                .toList());
        List<String> evaluated = RuleEngine.rules().stream().map(RuleDescription::id).toList();
        return transactions.execute(status -> {
            UUID id = store.save(claim.id(), consolidation,
                    new RiskStore.Versions(RuleEngine.RULESET_VERSION, scorer.config().version(), RiskFeatures.VERSION),
                    score, indicators, anomaly, evaluated);
            audit.record(AuditEntry.of("claim:" + claim.id(), AuditAction.CLAIM_RISK_SCREENED, AuditOutcome.SUCCESS)
                    .target("CLAIM", claim.id())
                    .detail(AuditDetail.PIPELINE_VERSION, RuleEngine.RULESET_VERSION)
                    .detail(AuditDetail.RISK_BAND, score.band()));
            return new Stored(id, score.band().name(), score.total(), score.route().name());
        });
    }

    /** A result for another claim, or with other features or detectors, is a defect: refuse it. */
    private static void checkAnomaly(UUID claimId, AnomalyResult anomaly) {
        boolean detectors = anomaly.detectors().size() == DETECTORS.size()
                && anomaly.detectors().stream().map(RiskScreening.DetectorResult::detector).collect(Collectors.toSet()).equals(DETECTORS);
        if (!claimId.equals(anomaly.claimId()) || !RiskFeatures.VERSION.equals(anomaly.featureVersion()) || !detectors) {
            throw new Failure("anomaly_result_mismatch", false, List.of());
        }
    }

    /**
     * Asks for a plain-language summary of the stored signals. If it cannot be had, the screening is
     * complete without it, and the reason is stored.
     */
    private void summarize(UUID claimId, Stored stored) {
        List<StoredSignal> signals = store.signals(stored.id());
        List<String> refs = signals.stream().map(StoredSignal::ref).toList();
        if (signals.isEmpty()) {
            store.saveSummary(stored.id(), "NOT_NEEDED", null, refs, List.of(), null, null);
            return;
        }
        List<RiskScreening.Signal> sent = new ArrayList<>();
        for (int index = 0; index < signals.size(); index++) {
            StoredSignal signal = signals.get(index);
            sent.add(new RiskScreening.Signal("S" + (index + 1), signal.source(), signal.kind(), signal.category(),
                    signal.severity(), signal.title(), signal.explanation(), signal.points()));
        }
        audit.recordIndependently(AuditEntry.of("claim:" + claimId, AuditAction.CLAIM_SENT_FOR_RISK_SUMMARY, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId));
        RiskScreening.SummaryResult result;
        try {
            result = ai.summarizeRisk(new RiskScreening.SummaryRequest(claimId, stored.band(), stored.score(), stored.route(), sent));
        } catch (Failure failure) {
            transactions.executeWithoutResult(status -> {
                runs.saveRuns(claimId, failure.runs());
                store.saveSummary(stored.id(), "UNAVAILABLE", null, refs, List.of(), failure.code(), null);
            });
            return;
        }
        boolean available = "AVAILABLE".equals(result.status()) && result.text() != null && refs.size() >= result.cited().size()
                && result.cited().stream().allMatch(id -> id.matches("S\\d+") && Integer.parseInt(id.substring(1)) <= refs.size());
        transactions.executeWithoutResult(status -> {
            runs.saveRuns(claimId, result.runs());
            store.saveSummary(stored.id(), available ? "AVAILABLE" : "UNAVAILABLE", available ? result.text() : null, refs,
                    available ? result.cited() : List.of(), available ? null : firstNonNull(result.problem(), "summary_rejected"),
                    result.promptVersion());
        });
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
