package dev.prism.core.policy.internal;

import dev.prism.core.policy.PolicyDirectory.VersionRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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

    UUID policyId() {
        return policyId;
    }

    int versionNumber() {
        return versionNumber;
    }

    VersionRef toRef() {
        return new VersionRef(id, versionNumber, effectiveFrom, effectiveTo, sumInsured, currency);
    }
}
