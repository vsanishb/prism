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

    /**
     * When continuous cover began, for this version: the start of the earliest version reached by
     * stepping back through versions that each begin the day after the previous one ends. A gap in
     * the register restarts cover. Waiting periods count from this date.
     */
    Optional<LocalDate> coverStart(UUID versionId);

    record PolicyRef(UUID id, String policyNumber, String productName, String policyholderName) {}

    record VersionRef(
            UUID id,
            int versionNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal sumInsured,
            String currency,
            String knowledgeStatus) {}
}
