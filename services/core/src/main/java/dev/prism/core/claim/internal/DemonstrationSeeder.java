package dev.prism.core.claim.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.prism.core.claim.internal.ClaimEntity.Details;
import dev.prism.core.claim.internal.ClaimEntity.Type;
import dev.prism.core.claim.internal.ClaimService.ClaimDetail;
import dev.prism.core.document.DocumentType;
import dev.prism.core.identity.SyntheticActors;
import dev.prism.core.platform.config.PrismProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the M7 risk demonstrations (D-088) from the generator's manifest, through the normal claim
 * service. Earlier claims are submitted, so they are processed when core next starts. Scenario claims
 * stay drafts until someone submits them from the demonstrations page.
 */
@Component
@Profile("seed")
@Order(40)
class DemonstrationSeeder implements ApplicationRunner {

    private static final String ACTOR = "claims.processor@synthetic.prism.test";

    private final ClaimService claims;
    private final SyntheticActors actors;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Path directory;

    DemonstrationSeeder(ClaimService claims, SyntheticActors actors, JdbcClient jdbc, JsonMapper json, PrismProperties properties) {
        this.claims = claims;
        this.actors = actors;
        this.jdbc = jdbc;
        this.json = json;
        this.directory = Path.of(properties.seed().documentsPath(), "claims", "risk-demo");
    }

    /** Only what seeding needs: the documents' contents are for the model stand-in. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(Policy policy, List<SeededClaim> claims) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Policy(String policyNumber, String sumInsured, String effectiveFrom, String effectiveTo) {}

    record SeededClaim(
            String scenario,
            String role,
            int position,
            String claimant,
            String patient,
            String hospital,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            BigDecimal claimedAmount,
            List<SeededDocument> documents) {}

    record SeededDocument(String file, DocumentType type) {}

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.sql("SELECT count(*) > 0 FROM core.demonstration_claims").query(Boolean.class).single()) {
            return;
        }
        Manifest manifest = read();
        UUID policy = policy(manifest.policy());
        actors.runAs(ACTOR, () -> {
            for (SeededClaim seeded : manifest.claims()) {
                ClaimDetail claim = claims.create(
                        new Details(Type.REIMBURSEMENT, policy, seeded.claimant(), seeded.patient(), seeded.hospital(),
                                seeded.admissionDate(), seeded.dischargeDate(), seeded.claimedAmount()),
                        UUID.randomUUID());
                UUID id = claim.summary().id();
                for (SeededDocument document : seeded.documents()) {
                    claims.addDocument(id, document.type(), directory.resolve(document.file()));
                }
                if ("EARLIER".equals(seeded.role())) {
                    claims.submit(id);
                }
                jdbc.sql("INSERT INTO core.demonstration_claims (scenario, role, position, claim_id) VALUES (?, ?, ?, ?)")
                        .params(seeded.scenario(), seeded.role(), seeded.position(), id)
                        .update();
            }
        });
    }

    private Manifest read() {
        try {
            return json.readValue(Files.readString(directory.resolve("manifest.json")), Manifest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The policy seeder registers it. The manifest's documents depend on its dates and sum insured, so they must agree. */
    private UUID policy(Policy expected) {
        return jdbc.sql("""
                        SELECT p.id FROM core.policies p
                        JOIN core.policy_versions v ON v.policy_id = p.id
                        WHERE p.policy_number = ? AND v.sum_insured = ? AND v.effective_from = ? AND v.effective_to = ?
                        """)
                .params(expected.policyNumber(), new BigDecimal(expected.sumInsured()), LocalDate.parse(expected.effectiveFrom()),
                        LocalDate.parse(expected.effectiveTo()))
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("The demonstration policy does not match the risk-demo manifest"));
    }
}
