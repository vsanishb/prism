package dev.prism.core.identity.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.identity.Role;
import dev.prism.core.identity.internal.AccountTokenEntity.Purpose;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.security.ActorPrincipal;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems invitation and password-reset links. Raw tokens exist only in the returned
 * link: storage holds a hash, and the link carries the token in the URL fragment, which browsers
 * do not send to servers, so it stays out of access logs.
 */
@Service
class AccountTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final String INVALID_LINK = "This link is invalid or has expired.";

    private final AccountTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionRevoker sessionRevoker;
    private final AuditRecorder audit;
    private final PrismProperties properties;
    private final SecureRandom random = new SecureRandom();

    AccountTokenService(
            AccountTokenRepository tokens,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionRevoker sessionRevoker,
            AuditRecorder audit,
            PrismProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionRevoker = sessionRevoker;
        this.audit = audit;
        this.properties = properties;
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public IssuedLink invite(String rawEmail, String fullName, Set<Role> roles) {
        UUID createdBy = CurrentActor.find().map(ActorPrincipal::userId).orElse(null);
        return issueInvitation(rawEmail, fullName, roles, createdBy);
    }

    /** Creates the first administrator's invitation. Runs at startup, with no signed-in user. */
    @Transactional
    public IssuedLink inviteFirstAdministrator(String rawEmail) {
        return issueInvitation(rawEmail, "Administrator", Set.of(Role.ADMIN), null);
    }

    private IssuedLink issueInvitation(String rawEmail, String fullName, Set<Role> roles, UUID createdBy) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("email_in_use", "An account with this email already exists.");
        }
        revokeOpen(Purpose.INVITATION, email);
        String token = newToken();
        Instant expiresAt = Instant.now().plus(properties.security().invitationValidity());
        AccountTokenEntity saved = tokens.save(
                AccountTokenEntity.invitation(hash(token), email, fullName.trim(), roles, createdBy, expiresAt));
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.INVITATION_CREATED, AuditOutcome.SUCCESS)
                .target("INVITATION", saved.id()));
        return new IssuedLink(saved.id(), link("accept-invite", token), expiresAt);
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public IssuedLink issuePasswordReset(UUID userId) {
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found."));
        if (!user.isActive()) {
            throw ApiException.conflict("user_inactive", "Reactivate this user before resetting their password.");
        }
        revokeOpen(Purpose.PASSWORD_RESET, user.email());
        String token = newToken();
        Instant expiresAt = Instant.now().plus(properties.security().passwordResetValidity());
        AccountTokenEntity saved = tokens.save(AccountTokenEntity.passwordReset(
                hash(token), user.email(), user.id(), CurrentActor.require().userId(), expiresAt));
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.PASSWORD_RESET_ISSUED, AuditOutcome.SUCCESS)
                .target("USER", user.id()));
        return new IssuedLink(saved.id(), link("reset-password", token), expiresAt);
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional(readOnly = true)
    public List<PendingInvitation> pendingInvitations() {
        return tokens.findAllOpen(Purpose.INVITATION).stream()
                .map(token -> new PendingInvitation(
                        token.id(), token.email(), token.fullName(), token.roles(), token.createdAt(), token.expiresAt()))
                .toList();
    }

    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @Transactional
    public void revokeInvitation(UUID invitationId) {
        AccountTokenEntity token = tokens.findById(invitationId)
                .filter(found -> found.purpose() == Purpose.INVITATION && found.isUsable(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("Invitation not found."));
        token.revoke(Instant.now());
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.INVITATION_REVOKED, AuditOutcome.SUCCESS)
                .target("INVITATION", token.id()));
    }

    @Transactional(readOnly = true)
    public TokenDetails inspect(String token) {
        AccountTokenEntity found = tokens.findByTokenHash(hash(token))
                .filter(candidate -> candidate.isUsable(Instant.now()))
                .orElseThrow(AccountTokenService::invalidLink);
        return new TokenDetails(found.purpose(), found.email(), found.fullName());
    }

    @Transactional
    public void redeem(String token, String password, String fullName) {
        AccountTokenEntity found = tokens.lockByTokenHash(hash(token))
                .filter(candidate -> candidate.isUsable(Instant.now()))
                .orElseThrow(AccountTokenService::invalidLink);
        passwordPolicy.validate(password, found.email());
        String passwordHash = passwordEncoder.encode(password);
        found.consume(Instant.now());

        if (found.purpose() == Purpose.INVITATION) {
            acceptInvitation(found, passwordHash, fullName);
        } else {
            completeReset(found, passwordHash);
        }
    }

    private void acceptInvitation(AccountTokenEntity token, String passwordHash, String fullName) {
        if (users.existsByEmail(token.email())) {
            throw invalidLink();
        }
        String name = fullName == null || fullName.isBlank() ? token.fullName() : fullName.trim();
        UserEntity user = users.save(new UserEntity(token.email(), name, passwordHash, token.roles()));
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.INVITATION_ACCEPTED, AuditOutcome.SUCCESS)
                .actor(user.id(), user.roles().stream().map(Role::name).toList())
                .target("USER", user.id()));
    }

    private void completeReset(AccountTokenEntity token, String passwordHash) {
        UserEntity user = users.findById(token.userId()).filter(UserEntity::isActive).orElseThrow(AccountTokenService::invalidLink);
        user.changePassword(passwordHash);
        sessionRevoker.revokeAll(user.id());
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.PASSWORD_RESET_COMPLETED, AuditOutcome.SUCCESS)
                .actor(user.id(), user.roles().stream().map(Role::name).toList())
                .target("USER", user.id()));
    }

    private void revokeOpen(Purpose purpose, String email) {
        // Flushed before the insert, because a unique index allows one open token per email.
        tokens.findOpen(purpose, email).ifPresent(open -> {
            open.revoke(Instant.now());
            tokens.flush();
        });
    }

    private String link(String path, String token) {
        return properties.webBaseUrl().resolve("/" + path) + "#token=" + token;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static ApiException invalidLink() {
        return ApiException.invalid("invalid_link", INVALID_LINK);
    }

    record IssuedLink(UUID id, String link, Instant expiresAt) {}

    record PendingInvitation(
            UUID id, String email, String fullName, Set<Role> roles, Instant createdAt, Instant expiresAt) {}

    record TokenDetails(Purpose purpose, String email, String fullName) {}
}
