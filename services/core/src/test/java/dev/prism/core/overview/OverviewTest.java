package dev.prism.core.overview;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.identity.Role;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The home page's figures: who may read them, and that drafts are counted only for staff who see drafts. */
class OverviewTest extends IntegrationTest {

    private static List<String> recentIds(JsonNode overview) {
        List<String> ids = new ArrayList<>();
        overview.get("recent").forEach(claim -> ids.add(claim.get("id").asString()));
        return ids;
    }

    @Test
    void draftsAreCountedOnlyForStaffWhoManageClaims() throws Exception {
        ClaimFixtures fixtures = claimFixtures();
        String draft = fixtures.draftClaimId();

        Client processor = fixtures.processor();
        JsonNode managing = processor.bodyOf(processor.getPath("/api/v1/overview"));
        assertThat(managing.get("claimsByStatus").get("DRAFT").asLong()).isPositive();
        assertThat(recentIds(managing)).contains(draft);

        Client reviewer = signedInAs(Role.REVIEWER);
        JsonNode reading = reviewer.bodyOf(reviewer.getPath("/api/v1/overview"));
        assertThat(reading.get("claimsByStatus").has("DRAFT")).isFalse();
        assertThat(recentIds(reading)).doesNotContain(draft);
        assertThat(reading.get("recent").size()).isLessThanOrEqualTo(6);
    }

    @Test
    void submittedClaimsCountAsOpenWork() throws Exception {
        ClaimFixtures fixtures = claimFixtures();
        fixtures.submittedClaimId();

        Client auditor = signedInAs(Role.AUDITOR);
        JsonNode overview = auditor.bodyOf(auditor.getPath("/api/v1/overview"));
        assertThat(overview.get("openClaimed")).isNotEmpty();
        assertThat(overview.get("openClaimed").get(0).get("currency").asString()).isEqualTo("INR");
        assertThat(overview.has("riskBands")).isTrue();
        assertThat(overview.has("outcomes")).isTrue();
    }

    @Test
    void staffWithoutClaimAccessAreRefused() throws Exception {
        Client administrator = signedInAs(Role.ADMIN);
        assertThat(administrator.getPath("/api/v1/overview").getStatus()).isEqualTo(403);
    }
}
