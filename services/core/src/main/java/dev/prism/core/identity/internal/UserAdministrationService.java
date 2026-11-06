package dev.prism.core.identity.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.identity.Role;
import dev.prism.core.identity.internal.UserEntity.Status;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.platform.web.Page;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserAdministrationService {

    private static final UUID FIRST_PAGE = new UUID(0, 0);
    private static final String ADMIN_GUARD_LOCK = "identity:last-admin-guard";

    private final UserRepository users;
    private final SessionRevoker sessionRevoker;
    private final AuditRecorder audit;
    private final JdbcClient jdbc;

    UserAdministrationService(
            UserRepository users, SessionRevoker sessionRevoker, AuditRecorder audit, JdbcClient jdbc) {
        this.users = users;
        this.sessionRevoker = sessionRevoker;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional(readOnly = true)
    public Page<UserView> list(String search, UUID after, int limit) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<UserEntity> rows = users.page(after == null ? FIRST_PAGE : after, term, Limit.of(limit + 1));
        boolean hasMore = rows.size() > limit;
        List<UserEntity> items = hasMore ? rows.subList(0, limit) : rows;
        return new Page<>(
                items.stream().map(UserView::of).toList(),
                hasMore ? items.getLast().id().toString() : null);
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public UserView changeRoles(UUID userId, Set<Role> roles) {
        UserEntity user = find(userId);
        Set<Role> before = user.roles();
        if (before.equals(roles)) {
            return UserView.of(user);
        }
        if (before.contains(Role.ADMIN) && !roles.contains(Role.ADMIN)) {
            guardAdministrator(user);
        }
        user.changeRoles(roles);
        sessionRevoker.revokeAll(user.id());
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.USER_ROLES_CHANGED, AuditOutcome.SUCCESS)
                .target("USER", user.id())
                .detail(AuditDetail.ROLES_BEFORE, before)
                .detail(AuditDetail.ROLES_AFTER, roles));
        return UserView.of(user);
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public UserView deactivate(UUID userId) {
        UserEntity user = find(userId);
        if (!user.isActive()) {
            return UserView.of(user);
        }
        if (user.roles().contains(Role.ADMIN)) {
            guardAdministrator(user);
        } else if (user.id().equals(CurrentActor.require().userId())) {
            throw ApiException.conflict("self_lockout", "You cannot deactivate your own account.");
        }
        user.changeStatus(Status.DEACTIVATED);
        sessionRevoker.revokeAll(user.id());
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.USER_DEACTIVATED, AuditOutcome.SUCCESS)
                .target("USER", user.id()));
        return UserView.of(user);
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public UserView reactivate(UUID userId) {
        UserEntity user = find(userId);
        if (!user.isActive()) {
            user.changeStatus(Status.ACTIVE);
            audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.USER_REACTIVATED, AuditOutcome.SUCCESS)
                    .target("USER", user.id()));
        }
        return UserView.of(user);
    }

    /**
     * Stops an administrator from removing their own access, and keeps one active administrator.
     * The lock serializes this check: otherwise two administrators could remove each other at once.
     */
    private void guardAdministrator(UserEntity user) {
        if (user.id().equals(CurrentActor.require().userId())) {
            throw ApiException.conflict("self_lockout", "You cannot remove your own administrator access.");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(ADMIN_GUARD_LOCK)
                .query(resultSet -> {});
        if (users.countActiveWithRoleExcluding(Role.ADMIN, user.id()) == 0) {
            throw ApiException.conflict("last_administrator", "Prism must keep at least one active administrator.");
        }
    }

    private UserEntity find(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found."));
    }

    record UserView(
            UUID id,
            String email,
            String fullName,
            Status status,
            Set<Role> roles,
            Instant createdAt,
            Instant lastSignInAt) {

        static UserView of(UserEntity user) {
            return new UserView(
                    user.id(),
                    user.email(),
                    user.fullName(),
                    user.status(),
                    user.roles(),
                    user.createdAt(),
                    user.lastSignInAt());
        }
    }
}
