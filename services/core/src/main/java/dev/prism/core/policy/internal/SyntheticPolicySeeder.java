package dev.prism.core.policy.internal;

import dev.prism.core.identity.SyntheticActors;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import dev.prism.core.policy.internal.PolicyService.PolicySummary;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Registers fictional policies through the normal service, so that they are validated and audited. */
@Component
@Profile("seed")
@Order(20)
class SyntheticPolicySeeder implements ApplicationRunner {

    private static final String ACTOR = "policy.manager@synthetic.prism.test";

    private final PolicyService policies;
    private final SyntheticActors actors;
    private final Path documents;

    SyntheticPolicySeeder(PolicyService policies, SyntheticActors actors, PrismProperties properties) {
        this.policies = policies;
        this.actors = actors;
        this.documents = Path.of(properties.seed().documentsPath(), "policies");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (policies.exists("SYN-FFG-2026-0001")) {
            return;
        }
        actors.runAs(ACTOR, () -> {
            PolicySummary gold = policies.create(
                    "SYN-FFG-2026-0001", "Family Floater Gold (Synthetic)", "Synthetic Policyholder One");
            addVersion(gold, "2025-04-01", "2026-03-31", "500000.00", "family-floater-gold-v1.pdf");
            addVersion(gold, "2026-04-01", "2027-03-31", "1000000.00", "family-floater-gold-v2.pdf");

            PolicySummary silver = policies.create(
                    "SYN-IND-2026-0002", "Individual Silver (Synthetic)", "Synthetic Policyholder Two");
            policies.addVersion(
                    silver.id(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), new BigDecimal("300000.00"));
        });
    }

    private void addVersion(PolicySummary policy, String from, String to, String sumInsured, String wording) {
        VersionRef version = policies.addVersion(
                policy.id(), LocalDate.parse(from), LocalDate.parse(to), new BigDecimal(sumInsured));
        policies.attachWording(policy.id(), version.id(), documents.resolve(wording));
    }
}
