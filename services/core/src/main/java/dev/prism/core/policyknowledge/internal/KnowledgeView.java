package dev.prism.core.policyknowledge.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything the review screens show for one policy version. */
record KnowledgeView(
        UUID versionId,
        int versionNumber,
        String knowledgeStatus,
        BigDecimal sumInsured,
        String currency,
        UUID documentId,
        String documentName,
        Integer pageCount,
        String uploadedByName,
        List<BatchView> batches,
        List<ProvisionView> provisions,
        List<RelationView> relations,
        List<FindingView> findings,
        List<Blocker> blockers,
        String publishedByName,
        Instant publishedAt,
        String rejectionReason) {

    KnowledgeView withBlockers(List<Blocker> next) {
        return new KnowledgeView(
                versionId, versionNumber, knowledgeStatus, sumInsured, currency, documentId, documentName, pageCount,
                uploadedByName, batches, provisions, relations, findings, next, publishedByName, publishedAt,
                rejectionReason);
    }

    record BatchView(int firstPage, int lastPage, String status, String detailCode) {}

    record ProvisionView(
            UUID id,
            String key,
            String type,
            String title,
            String text,
            int pageStart,
            int pageEnd,
            String quote,
            List<BigDecimal> region,
            String grounding,
            boolean textVerified,
            String parentKey,
            List<String> exceptions,
            List<ValueView> values) {}

    record ValueView(
            UUID id,
            String name,
            String kind,
            String rawValue,
            String value,
            String unit,
            String qualifier,
            String state,
            Integer page,
            String quote,
            List<BigDecimal> region,
            String grounding,
            String problem,
            boolean critical,
            ReviewView review) {}

    record ReviewView(String decision, String correctedValue, String note, String reviewerName, Instant reviewedAt) {}

    record RelationView(UUID fromProvisionId, String targetKey, UUID toProvisionId, String relation, String origin, boolean statedInText) {}

    record FindingView(
            String code, String severity, UUID provisionId, UUID valueId, String expectedValue, String actualValue) {}

    record Blocker(String code, String message) {}
}
