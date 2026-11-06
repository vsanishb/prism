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
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
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
class SyntheticUserSeeder implements ApplicationRunner {

    private static final List<Seed> SEEDS = List.of(
            new Seed("admin", "Synthetic Administrator", Role.ADMIN),
            new Seed("policy.manager", "Synthetic Policy Manager", Role.POLICY_MANAGER),
            new Seed("claims.processor", "Synthetic Claims Processor", Role.CLAIMS_PROCESSOR),
            new Seed("reviewer", "Synthetic Reviewer", Role.REVIEWER),
            new Seed("senior.reviewer", "Synthetic Senior Reviewer", Role.SENIOR_REVIEWER),
            new Seed("investigator", "Synthetic Fraud Investigator", Role.FRAUD_INVESTIGATOR),
            new Seed("auditor", "Synthetic Auditor", Role.AUDITOR));

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
                # Synthetic test accounts

                Local testing only. These accounts are synthetic, and the passwords change on every seed.

                | Role | Email | Password |
                |---|---|---|
                """);
        for (Seed seed : SEEDS) {
            String email = seed.localPart() + "@synthetic.prism.test";
            String password = randomPassword();
            transactions.executeWithoutResult(status -> upsert(seed, email, password));
            sheet.append("| %s | %s | `%s` |%n".formatted(seed.role(), email, password));
        }
        Path path = Path.of(file);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, sheet);
    }

    private void upsert(Seed seed, String email, String password) {
        String hash = encoder.encode(password);
        users.findByEmail(email).ifPresentOrElse(existing -> existing.changePassword(hash), () -> {
            UserEntity created = users.save(new UserEntity(email, seed.fullName(), hash, Set.of(seed.role())));
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

    private record Seed(String localPart, String fullName, Role role) {}
}
