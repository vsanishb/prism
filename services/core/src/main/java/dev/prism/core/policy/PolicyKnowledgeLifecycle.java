package dev.prism.core.policy;

import java.util.UUID;

/**
 * Knowledge status changes for a policy version, owned here because the version is. Each change is
 * audited in the caller's transaction. The caller enforces permissions and review rules.
 */
public interface PolicyKnowledgeLifecycle {

    VersionState state(UUID versionId);

    /** Moves PROCESSING to IN_REVIEW. Does nothing in any other state, as activities can repeat. */
    void readyForReview(UUID versionId);

    void publish(UUID versionId, UUID publisher);

    void reject(UUID versionId, String reason);

    record VersionState(
            UUID versionId,
            UUID policyId,
            int versionNumber,
            String knowledgeStatus,
            java.math.BigDecimal sumInsured,
            String currency,
            UUID publishedBy,
            java.time.Instant publishedAt,
            String rejectionReason) {}
}
