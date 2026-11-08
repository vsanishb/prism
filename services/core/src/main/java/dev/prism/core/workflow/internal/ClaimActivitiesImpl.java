package dev.prism.core.workflow.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimProcessing;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.intelligence.ClaimIntelligence.Consolidation;
import dev.prism.core.workflow.internal.StageRecorder.Stage;
import dev.prism.core.workflow.internal.StageRecorder.Status;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ClaimActivitiesImpl implements ClaimActivities {

    private final ClaimProcessing claims;
    private final ClaimIntelligence intelligence;
    private final StageRecorder stages;
    private final AuditRecorder audit;

    ClaimActivitiesImpl(
            ClaimProcessing claims, ClaimIntelligence intelligence, StageRecorder stages, AuditRecorder audit) {
        this.claims = claims;
        this.intelligence = intelligence;
        this.stages = stages;
        this.audit = audit;
    }

    @Override
    public void begin(String claimId) {
        claims.begin(UUID.fromString(claimId));
    }

    @Override
    public List<String> documentsToExtract(String claimId) {
        return intelligence.documentsToExtract(UUID.fromString(claimId)).stream().map(UUID::toString).toList();
    }

    @Override
    public boolean consolidate(String claimId) {
        UUID id = UUID.fromString(claimId);
        stages.record(id, Stage.CONSOLIDATION, null, Status.PROCESSING, null);
        Consolidation result = intelligence.consolidate(id);
        stages.record(id, Stage.CONSOLIDATION, null, Status.COMPLETED, null);
        stages.record(
                id,
                Stage.VALIDATION,
                null,
                result.needsAttention() ? Status.REQUIRES_REVIEW : Status.COMPLETED,
                null);
        return result.complete();
    }

    @Override
    public void awaitInformation(String claimId) {
        claims.awaitInformation(UUID.fromString(claimId));
    }

    @Override
    public void readyForReview(String claimId) {
        claims.readyForReview(UUID.fromString(claimId));
    }

    @Override
    public void recordExtractionFailed(String claimId, String documentId, String code) {
        stages.record(UUID.fromString(claimId), Stage.DOCUMENT_EXTRACTION, UUID.fromString(documentId), Status.FAILED, code);
    }

    @Override
    public void recordConsolidationFailed(String claimId, String code) {
        stages.record(UUID.fromString(claimId), Stage.CONSOLIDATION, null, Status.FAILED, code);
    }

    @Override
    public void recordAnalysisFailed(String claimId, String code) {
        stages.record(UUID.fromString(claimId), Stage.POLICY_ANALYSIS, null, Status.FAILED, code);
    }

    @Override
    public void recordRiskScreeningFailed(String claimId, String code) {
        stages.record(UUID.fromString(claimId), Stage.RISK_SCREENING, null, Status.FAILED, code);
    }

    @Override
    public void recordInformationWaitExpired(String claimId) {
        audit.recordIndependently(AuditEntry.of(
                        "claim:" + claimId, AuditAction.CLAIM_INFORMATION_WAIT_EXPIRED, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId));
    }
}
