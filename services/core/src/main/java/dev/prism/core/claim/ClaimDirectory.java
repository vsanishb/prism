package dev.prism.core.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Claim details for other modules. */
public interface ClaimDirectory {

    /** Applies the current user's access rules, and throws if they may not see this claim. */
    ClaimRecord visible(UUID claimId);

    /** For system processes, which act without a user. Performs no authorization. */
    ClaimRecord get(UUID claimId);

    record ClaimRecord(
            UUID id,
            String type,
            String status,
            boolean closed,
            String claimantName,
            String patientName,
            String hospitalName,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            BigDecimal claimedAmount,
            String currency,
            UUID policyId,
            UUID policyVersionId) {}
}
