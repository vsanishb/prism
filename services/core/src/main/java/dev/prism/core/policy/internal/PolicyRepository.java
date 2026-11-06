package dev.prism.core.policy.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PolicyRepository extends JpaRepository<PolicyEntity, UUID> {

    boolean existsByPolicyNumber(String policyNumber);

    @Query("""
            SELECT p FROM PolicyEntity p
            WHERE p.id > :after
              AND (:search = '' OR lower(p.policyNumber) LIKE concat('%', :search, '%')
                   OR lower(p.productName) LIKE concat('%', :search, '%')
                   OR lower(p.policyholderName) LIKE concat('%', :search, '%'))
            ORDER BY p.id
            """)
    List<PolicyEntity> page(@Param("after") UUID after, @Param("search") String search, Limit limit);

    /** Serializes version numbering for one policy. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PolicyEntity p WHERE p.id = :id")
    Optional<PolicyEntity> lockById(@Param("id") UUID id);
}
