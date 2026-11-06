package dev.prism.core.policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Policy lookups for other modules. */
public interface PolicyDirectory {

    Optional<PolicyRef> findPolicy(UUID policyId);

    /** The single version in force on a date. Versions cannot overlap, so there is at most one. */
    Optional<VersionRef> versionCovering(UUID policyId, LocalDate date);

    Optional<VersionRef> findVersion(UUID versionId);

    record PolicyRef(UUID id, String policyNumber, String productName, String policyholderName) {}

    record VersionRef(
            UUID id,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal sumInsured,
            String currency) {}
}
