package dev.prism.core.identity.internal;

import dev.prism.core.identity.internal.AccountTokenEntity.Purpose;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountTokenRepository extends JpaRepository<AccountTokenEntity, UUID> {

    Optional<AccountTokenEntity> findByTokenHash(byte[] tokenHash);

    /** Locks the row so that two concurrent redemptions cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AccountTokenEntity t WHERE t.tokenHash = :tokenHash")
    Optional<AccountTokenEntity> lockByTokenHash(@Param("tokenHash") byte[] tokenHash);

    @Query("""
            SELECT t FROM AccountTokenEntity t
            WHERE t.purpose = :purpose AND t.email = :email
              AND t.consumedAt IS NULL AND t.revokedAt IS NULL
            """)
    Optional<AccountTokenEntity> findOpen(@Param("purpose") Purpose purpose, @Param("email") String email);

    @Query("""
            SELECT t FROM AccountTokenEntity t
            WHERE t.purpose = :purpose AND t.consumedAt IS NULL AND t.revokedAt IS NULL
            ORDER BY t.createdAt DESC
            """)
    List<AccountTokenEntity> findAllOpen(@Param("purpose") Purpose purpose);
}
