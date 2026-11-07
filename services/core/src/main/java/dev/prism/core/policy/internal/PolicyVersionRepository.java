package dev.prism.core.policy.internal;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PolicyVersionRepository extends JpaRepository<PolicyVersionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM PolicyVersionEntity v WHERE v.id = :id")
    Optional<PolicyVersionEntity> lockById(@Param("id") UUID id);

    List<PolicyVersionEntity> findByPolicyIdOrderByVersionNumberDesc(UUID policyId);

    @Query("""
            SELECT v FROM PolicyVersionEntity v
            WHERE v.policyId = :policyId AND v.effectiveFrom <= :date AND v.effectiveTo >= :date
            """)
    Optional<PolicyVersionEntity> findCovering(@Param("policyId") UUID policyId, @Param("date") LocalDate date);

    @Query("SELECT coalesce(max(v.versionNumber), 0) FROM PolicyVersionEntity v WHERE v.policyId = :policyId")
    int highestVersionNumber(@Param("policyId") UUID policyId);
}
