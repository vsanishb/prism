package dev.prism.core.policy.internal;

import dev.prism.core.policy.PolicyDirectory.VersionRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import dev.prism.core.platform.web.ApiException;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "policy_versions", schema = "core")
class PolicyVersionEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "version_number")
    private int versionNumber;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "sum_insured")
    private BigDecimal sumInsured;

    private String currency;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_status")
    private KnowledgeStatus knowledgeStatus = KnowledgeStatus.NO_WORDING;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    protected PolicyVersionEntity() {}

    PolicyVersionEntity(
            UUID policyId,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal sumInsured,
            String currency,
            UUID createdBy) {
        this.policyId = policyId;
        this.versionNumber = versionNumber;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.sumInsured = sumInsured;
        this.currency = currency;
        this.createdBy = createdBy;
    }

    UUID id() {
        return id;
    }

    LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    LocalDate effectiveTo() {
        return effectiveTo;
    }

    UUID policyId() {
        return policyId;
    }

    int versionNumber() {
        return versionNumber;
    }

    KnowledgeStatus knowledgeStatus() {
        return knowledgeStatus;
    }

    void changeKnowledgeStatus(KnowledgeStatus next) {
        this.knowledgeStatus = next;
    }

    /** A new wording restarts ingestion, unless the version is already published. */
    void wordingAttached() {
        if (knowledgeStatus.closed()) {
            throw ApiException.conflict("version_published", "A published version cannot change. Add a new version instead.");
        }
        this.knowledgeStatus = KnowledgeStatus.PROCESSING;
        this.rejectionReason = null;
    }

    void publish(UUID publisher, Instant at) {
        this.knowledgeStatus = KnowledgeStatus.PUBLISHED;
        this.publishedBy = publisher;
        this.publishedAt = at;
    }

    void reject(String reason) {
        this.knowledgeStatus = KnowledgeStatus.REJECTED;
        this.rejectionReason = reason;
    }

    VersionRef toRef() {
        return new VersionRef(id, versionNumber, effectiveFrom, effectiveTo, sumInsured, currency, knowledgeStatus.name());
    }

    dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState toState() {
        return new dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState(
                id, policyId, versionNumber, knowledgeStatus.name(), sumInsured, currency, publishedBy, publishedAt,
                rejectionReason);
    }
}
