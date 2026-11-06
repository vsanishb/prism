package dev.prism.core.policy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "policies", schema = "core")
class PolicyEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "policyholder_name")
    private String policyholderName;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyEntity() {}

    PolicyEntity(String policyNumber, String productName, String policyholderName, UUID createdBy) {
        this.policyNumber = policyNumber;
        this.productName = productName;
        this.policyholderName = policyholderName;
        this.createdBy = createdBy;
    }

    UUID id() {
        return id;
    }

    String policyNumber() {
        return policyNumber;
    }

    String productName() {
        return productName;
    }

    String policyholderName() {
        return policyholderName;
    }

    Instant createdAt() {
        return createdAt;
    }
}
