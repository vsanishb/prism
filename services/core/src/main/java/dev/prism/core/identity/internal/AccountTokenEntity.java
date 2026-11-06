package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "account_tokens", schema = "core")
class AccountTokenEntity {

    enum Purpose {
        INVITATION,
        PASSWORD_RESET
    }

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    @Column(name = "token_hash")
    private byte[] tokenHash;

    private String email;

    @Column(name = "full_name")
    private String fullName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] roles = new String[0];

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccountTokenEntity() {}

    static AccountTokenEntity invitation(
            byte[] tokenHash, String email, String fullName, Set<Role> roles, UUID createdBy, Instant expiresAt) {
        AccountTokenEntity token = new AccountTokenEntity();
        token.purpose = Purpose.INVITATION;
        token.tokenHash = tokenHash;
        token.email = email;
        token.fullName = fullName;
        token.roles = roles.stream().map(Role::name).sorted().toArray(String[]::new);
        token.createdBy = createdBy;
        token.expiresAt = expiresAt;
        return token;
    }

    static AccountTokenEntity passwordReset(
            byte[] tokenHash, String email, UUID userId, UUID createdBy, Instant expiresAt) {
        AccountTokenEntity token = new AccountTokenEntity();
        token.purpose = Purpose.PASSWORD_RESET;
        token.tokenHash = tokenHash;
        token.email = email;
        token.userId = userId;
        token.createdBy = createdBy;
        token.expiresAt = expiresAt;
        return token;
    }

    boolean isUsable(Instant now) {
        return consumedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void consume(Instant at) {
        this.consumedAt = at;
    }

    void revoke(Instant at) {
        this.revokedAt = at;
    }

    UUID id() {
        return id;
    }

    Purpose purpose() {
        return purpose;
    }

    String email() {
        return email;
    }

    String fullName() {
        return fullName;
    }

    Set<Role> roles() {
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        Arrays.stream(roles).map(Role::valueOf).forEach(parsed::add);
        return parsed;
    }

    UUID userId() {
        return userId;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}
