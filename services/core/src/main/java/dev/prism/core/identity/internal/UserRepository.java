package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            SELECT u FROM UserEntity u
            WHERE u.id > :after
              AND (:search = '' OR lower(u.fullName) LIKE concat('%', :search, '%')
                   OR u.email LIKE concat('%', :search, '%'))
            ORDER BY u.id
            """)
    List<UserEntity> page(@Param("after") UUID after, @Param("search") String search, Limit limit);

    @Query("""
            SELECT count(u) FROM UserEntity u JOIN u.roles r
            WHERE r = :role AND u.status = dev.prism.core.identity.internal.UserEntity.Status.ACTIVE
              AND u.id <> :excluded
            """)
    long countActiveWithRoleExcluding(@Param("role") Role role, @Param("excluded") UUID excluded);
}
