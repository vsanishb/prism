package dev.prism.core.identity.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.identity.Role;
import dev.prism.core.platform.config.PrismProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates one clearly synthetic staff account per role for local testing. Passwords are random,
 * regenerated on every run, and written only to a gitignored file.
 */
@Component
@Profile("seed")
@Order(10)
class SyntheticUserSeeder implements ApplicationRunner {

    static final List<Seed> SEEDS = List.of(
            new Seed("admin", "Karan Malhotra", Role.ADMIN),
            new Seed("policy.manager", "Priya Sharma", Role.POLICY_MANAGER),
            // A second manager, because the person who uploads a wording may not publish it.
            new Seed("policy.approver", "Deepak Menon", Role.POLICY_MANAGER),
            new Seed("claims.processor", "Neha Gupta", Role.CLAIMS_PROCESSOR),
            new Seed("reviewer", "Rahul Verma", Role.REVIEWER),
            new Seed("senior.reviewer", "Shalini Iyer", Role.SENIOR_REVIEWER),
            new Seed("investigator", "Farah Qureshi", Role.FRAUD_INVESTIGATOR),
            new Seed("auditor", "Mohan Pillai", Role.AUDITOR),
            // Every role, so every permission (D-101). Separation of duties still applies to it, as to
            // anyone: it cannot review a claim it created, or publish a wording it uploaded.
            new Seed("superuser", "Siddharth Rao", "SUPERUSER", EnumSet.allOf(Role.class)));

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final PrismProperties properties;

    SyntheticUserSeeder(
            UserRepository users,
            PasswordEncoder encoder,
            AuditRecorder audit,
            TransactionTemplate transactions,
            PrismProperties properties) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.isLocal()) {
            throw new IllegalStateException("Synthetic users may only be seeded when PRISM_ENVIRONMENT is local");
        }
        String file = properties.seed().credentialsFile();
        if (!StringUtils.hasText(file)) {
            throw new IllegalStateException("PRISM_SEED_CREDENTIALS_FILE is required for seeding");
        }

        StringBuilder sheet = new StringBuilder("""
                # Staff accounts

                Local environment only. The passwords change on every seed.

                | Role | Email | Password |
                |---|---|---|
                """);
        for (Seed seed : SEEDS) {
            String email = seed.localPart() + "@prism.local";
            String password = randomPassword();
            transactions.executeWithoutResult(status -> upsert(seed, email, password));
            sheet.append("| %s | %s | `%s` |%n".formatted(seed.label(), email, password));
        }
        Path path = Path.of(file);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, sheet);
    }

    private void upsert(Seed seed, String email, String password) {
        String hash = encoder.encode(password);
        users.findByEmail(email).ifPresentOrElse(existing -> existing.changePassword(hash), () -> {
            UserEntity created = users.save(new UserEntity(email, seed.fullName(), hash, seed.roles()));
            audit.record(AuditEntry.of(
                            AuditEntry.IDENTITY_STREAM, AuditAction.SYNTHETIC_USER_SEEDED, AuditOutcome.SUCCESS)
                    .target("USER", created.id()));
        });
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** @param label what the credentials sheet shows in its Role column */
    record Seed(String localPart, String fullName, String label, Set<Role> roles) {

        Seed(String localPart, String fullName, Role role) {
            this(localPart, fullName, role.name(), Set.of(role));
        }
    }
}
