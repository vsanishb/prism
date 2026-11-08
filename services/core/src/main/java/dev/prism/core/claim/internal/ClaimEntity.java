package dev.prism.core.claim.internal;

import dev.prism.core.platform.web.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "claims", schema = "core")
class ClaimEntity {

    enum Type {
        REIMBURSEMENT,
        CASHLESS
    }

    enum WithdrawalReason {
        CLAIMANT_REQUEST,
        DUPLICATE_ENTRY,
        ENTERED_IN_ERROR
    }

    record Details(
            Type type,
            UUID policyId,
            String claimantName,
            String patientName,
            String hospitalName,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            BigDecimal claimedAmount) {}

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "claim_number", updatable = false)
    private String claimNumber;

    @Enumerated(EnumType.STRING)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type")
    private Type type;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "policy_version_id")
    private UUID policyVersionId;

    @Column(name = "claimant_name")
    private String claimantName;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "hospital_name")
    private String hospitalName;

    @Column(name = "admission_date")
    private LocalDate admissionDate;

    @Column(name = "discharge_date")
    private LocalDate dischargeDate;

    @Column(name = "claimed_amount")
    private BigDecimal claimedAmount;

    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_reason")
    private WithdrawalReason withdrawalReason;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Version
    private long version;

    protected ClaimEntity() {}

    ClaimEntity(String claimNumber, Details details, String currency, UUID idempotencyKey, UUID createdBy) {
        this.claimNumber = claimNumber;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
        apply(details);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    final void apply(Details details) {
        this.type = details.type();
        this.policyId = details.policyId();
        this.claimantName = details.claimantName().trim();
        this.patientName = details.patientName().trim();
        this.hospitalName = details.hospitalName().trim();
        this.admissionDate = details.admissionDate();
        this.dischargeDate = details.dischargeDate();
        this.claimedAmount = details.claimedAmount();
    }

    void submit(UUID versionId, UUID actor, Instant at) {
        transitionTo(ClaimStatus.SUBMITTED);
        this.policyVersionId = versionId;
        this.submittedBy = actor;
        this.submittedAt = at;
    }

    void withdraw(WithdrawalReason reason) {
        transitionTo(ClaimStatus.WITHDRAWN);
        this.withdrawalReason = reason;
    }

    void advanceTo(ClaimStatus next) {
        transitionTo(next);
    }

    private void transitionTo(ClaimStatus next) {
        if (!status.canBecome(next)) {
            throw ApiException.conflict("invalid_transition", "This action is not available for a claim in its current state.");
        }
        this.status = next;
    }

    UUID id() {
        return id;
    }

    UUID createdBy() {
        return createdBy;
    }

    String claimNumber() {
        return claimNumber;
    }

    ClaimStatus status() {
        return status;
    }

    Type type() {
        return type;
    }

    UUID policyId() {
        return policyId;
    }

    UUID policyVersionId() {
        return policyVersionId;
    }

    String claimantName() {
        return claimantName;
    }

    String patientName() {
        return patientName;
    }

    String hospitalName() {
        return hospitalName;
    }

    LocalDate admissionDate() {
        return admissionDate;
    }

    LocalDate dischargeDate() {
        return dischargeDate;
    }

    BigDecimal claimedAmount() {
        return claimedAmount;
    }

    String currency() {
        return currency;
    }

    WithdrawalReason withdrawalReason() {
        return withdrawalReason;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant submittedAt() {
        return submittedAt;
    }

    long version() {
        return version;
    }
}
