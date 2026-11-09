package dev.prism.core.claim.internal;

import dev.prism.core.claim.internal.ClaimEntity.Details;
import dev.prism.core.claim.internal.ClaimEntity.Type;
import dev.prism.core.claim.internal.ClaimEntity.WithdrawalReason;
import dev.prism.core.claim.internal.ClaimService.ClaimDetail;
import dev.prism.core.document.DocumentType;
import dev.prism.core.identity.SyntheticActors;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.web.ApiException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Creates fictional claims in several states, through the normal service. */
@Component
@Profile("seed")
@Order(30)
class SyntheticClaimSeeder implements ApplicationRunner {

    private static final String ACTOR = "claims.processor@prism.local";
    private static final String HOSPITAL = "Vrinda Care Hospital, Pune";

    private final ClaimService claims;
    private final SyntheticActors actors;
    private final JdbcClient jdbc;
    private final Path appendectomy;
    private final Path scanned;

    SyntheticClaimSeeder(ClaimService claims, SyntheticActors actors, JdbcClient jdbc, PrismProperties properties) {
        this.claims = claims;
        this.actors = actors;
        this.jdbc = jdbc;
        this.appendectomy = Path.of(properties.seed().documentsPath(), "claims", "appendectomy");
        this.scanned = Path.of(properties.seed().documentsPath(), "claims", "scanned");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (claims.anyExist()) {
            return;
        }
        UUID policy = jdbc.sql("SELECT id FROM core.policies WHERE policy_number = 'NGI-FFG-2026-0001'")
                .query(UUID.class)
                .single();
        actors.runAs(ACTOR, () -> {
            ClaimDetail submitted = create(policy, "Asha Kulkarni", "106000.00", "2026-03-14", "2026-03-17");
            UUID submittedId = submitted.summary().id();
            claims.addDocument(submittedId, DocumentType.CLAIM_FORM, appendectomy.resolve("claim-form.pdf"));
            claims.addDocument(submittedId, DocumentType.HOSPITAL_BILL, appendectomy.resolve("hospital-bill.pdf"));
            claims.addDocument(submittedId, DocumentType.DISCHARGE_SUMMARY, appendectomy.resolve("discharge-summary.pdf"));
            claims.addDocument(submittedId, DocumentType.PRESCRIPTION, scanned.resolve("prescription.png"));
            claims.submit(submittedId);

            ClaimDetail withdrawn = create(policy, "Ravi Shankar", "24500.00", "2026-05-02", "2026-05-03");
            claims.withdraw(withdrawn.summary().id(), WithdrawalReason.ENTERED_IN_ERROR);

            ClaimDetail draft = create(policy, "Meera Nambiar", "68250.50", "2026-06-10", "2026-06-12");
            claims.addDocument(draft.summary().id(), DocumentType.DISCHARGE_SUMMARY, scanned.resolve("discharge-note.tiff"));

            create(policy, "Kabir Sethi", "15000.00", "2026-07-21", "2026-07-21");
        });
    }

    private ClaimDetail create(UUID policy, String patient, String amount, String admission, String discharge) {
        return claims.create(
                new Details(
                        Type.REIMBURSEMENT,
                        policy,
                        "Rajesh Iyer",
                        patient,
                        HOSPITAL,
                        LocalDate.parse(admission),
                        LocalDate.parse(discharge),
                        new BigDecimal(amount)),
                UUID.randomUUID());
    }
}
