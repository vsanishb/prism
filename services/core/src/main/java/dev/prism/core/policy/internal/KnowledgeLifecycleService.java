package dev.prism.core.policy.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.policy.PolicyKnowledgeLifecycle;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class KnowledgeLifecycleService implements PolicyKnowledgeLifecycle {

    private final PolicyVersionRepository versions;
    private final AuditRecorder audit;

    KnowledgeLifecycleService(PolicyVersionRepository versions, AuditRecorder audit) {
        this.versions = versions;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public VersionState state(UUID versionId) {
        return versions.findById(versionId)
                .map(PolicyVersionEntity::toState)
                .orElseThrow(() -> ApiException.notFound("Policy version not found."));
    }

    @Override
    @Transactional
    public void readyForReview(UUID versionId) {
        PolicyVersionEntity version = locked(versionId);
        if (version.knowledgeStatus() == KnowledgeStatus.PROCESSING) {
            version.changeKnowledgeStatus(KnowledgeStatus.IN_REVIEW);
            record(version, AuditAction.POLICY_KNOWLEDGE_READY_FOR_REVIEW);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID versionId, UUID publisher) {
        PolicyVersionEntity version = locked(versionId);
        requireInReview(version);
        version.publish(publisher, Instant.now());
        record(version, AuditAction.POLICY_VERSION_PUBLISHED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(UUID versionId, String reason) {
        PolicyVersionEntity version = locked(versionId);
        requireInReview(version);
        version.reject(reason);
        record(version, AuditAction.POLICY_VERSION_REJECTED);
    }

    private static void requireInReview(PolicyVersionEntity version) {
        if (version.knowledgeStatus() != KnowledgeStatus.IN_REVIEW) {
            throw ApiException.conflict("not_in_review", "Only a version in review can be published or rejected.");
        }
    }

    private PolicyVersionEntity locked(UUID versionId) {
        return versions.lockById(versionId).orElseThrow(() -> ApiException.notFound("Policy version not found."));
    }

    private void record(PolicyVersionEntity version, AuditAction action) {
        audit.record(AuditEntry.of("policy:" + version.policyId(), action, AuditOutcome.SUCCESS)
                .target("POLICY_VERSION", version.id())
                .detail(AuditDetail.VERSION_NUMBER, version.versionNumber())
                .detail(AuditDetail.STATUS_AFTER, version.knowledgeStatus()));
    }
}
