package dev.prism.core.claim.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ClaimRepository extends JpaRepository<ClaimEntity, UUID> {

    Optional<ClaimEntity> findByCreatedByAndIdempotencyKey(UUID createdBy, UUID idempotencyKey);

    /** Claim changes are serialized, so that a status check cannot race with a document upload. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ClaimEntity c WHERE c.id = :id")
    Optional<ClaimEntity> lockById(@Param("id") UUID id);

    /** Newest first: IDs are time-ordered UUIDv7, so they double as a stable cursor. */
    @Query("""
            SELECT c FROM ClaimEntity c
            WHERE c.id < :before
              AND (:includeDrafts = TRUE OR c.status <> dev.prism.core.claim.internal.ClaimStatus.DRAFT)
              AND (:filterStatus = FALSE OR c.status = :status)
              AND (:search = '' OR lower(c.claimNumber) LIKE concat('%', :search, '%')
                   OR lower(c.patientName) LIKE concat('%', :search, '%')
                   OR lower(c.claimantName) LIKE concat('%', :search, '%'))
            ORDER BY c.id DESC
            """)
    List<ClaimEntity> page(
            @Param("before") UUID before,
            @Param("includeDrafts") boolean includeDrafts,
            @Param("filterStatus") boolean filterStatus,
            @Param("status") ClaimStatus status,
            @Param("search") String search,
            Limit limit);

    @Query(value = "SELECT nextval('core.claim_number_seq')", nativeQuery = true)
    long nextClaimNumber();
}
