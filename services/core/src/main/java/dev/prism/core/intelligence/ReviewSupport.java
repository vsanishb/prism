package dev.prism.core.intelligence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What human review needs from claim intelligence: the records a decision is made with, fact
 * corrections, and the evidence an outcome traces back to. Authorization is the caller's: review
 * checks who may act before calling here.
 */
public interface ReviewSupport {

    /** The latest consolidation, policy analysis, and risk screening. Any of them may be absent. */
    CurrentRecords current(UUID claimId);

    /**
     * Records a correction of a fact in the claim's latest consolidation, as a new version. It applies
     * from the next consolidation, which the reviewer chooses to run. Joins the caller's transaction.
     *
     * @throws dev.prism.core.platform.web.ApiException if the fact is not current or the value does not parse
     */
    UUID correctFact(UUID claimId, UUID factId, String value, String reason, UUID correctedBy);

    /** Every correction, newest first, and whether the latest consolidation already uses it. */
    List<Correction> corrections(UUID claimId);

    /** The facts, sources, and AI runs behind a decision made on this consolidation. */
    Evidence evidence(UUID claimId, UUID consolidationId);

    /** @param riskBand null when the claim has not been screened */
    record CurrentRecords(
            UUID consolidationId, Integer sequence, UUID policyAssessmentId, UUID riskAssessmentId, String riskBand) {}

    record Correction(
            UUID id,
            String fieldName,
            UUID documentId,
            String originalValue,
            String value,
            String reason,
            String correctedBy,
            Instant createdAt,
            boolean applied) {}

    record Evidence(
            Integer sequence,
            Instant consolidatedAt,
            List<EvidenceFact> facts,
            AssessmentRef policyAssessment,
            ScreeningRef riskScreening,
            List<ModelRunRef> modelRuns,
            List<DocumentRef> documents) {}

    record EvidenceFact(String name, String state, String value, boolean corrected, List<SourceRef> sources) {}

    record SourceRef(UUID documentId, Integer page, String quote, List<BigDecimal> region) {}

    record AssessmentRef(UUID id, String pipelineVersion, Instant createdAt, int findings, int supported) {}

    record ScreeningRef(UUID id, String band, int score, String rulesetVersion, String modelVersion, int indicators) {}

    record ModelRunRef(String task, String model, String promptVersion, String outcome, Instant recordedAt) {}

    record DocumentRef(UUID id, String filename, String documentType, String sha256, Instant uploadedAt) {}
}
