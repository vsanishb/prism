package dev.prism.core.policy;

import static dev.prism.core.support.ClaimFixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.identity.Role;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

class PolicyRegisterTest extends IntegrationTest {

    private static final String WORDING = "policies/family-floater-gold-v1.pdf";

    private ClaimFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = claimFixtures();
    }

    private MockHttpServletResponse addVersion(String policy, String from, String to) throws Exception {
        return fixtures.policyManager().postJson(
                "/api/v1/policies/" + policy + "/versions",
                Map.of("effectiveFrom", from, "effectiveTo", to, "sumInsured", "100000.00"));
    }

    private JsonNode detail(String policy) throws Exception {
        Client manager = fixtures.policyManager();
        return manager.bodyOf(manager.getPath("/api/v1/policies/" + policy));
    }

    @Test
    void createsAPolicyAndNumbersItsVersions() throws Exception {
        String policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2025-01-01", "2025-12-31");
        fixtures.addVersion(policy, "2026-01-01", "2026-12-31");

        JsonNode versions = detail(policy).get("versions");

        assertThat(versions.valueStream().map(item -> item.get("version").get("versionNumber").asInt()))
                .containsExactly(2, 1);
        assertThat(versions.get(0).get("version").get("currency").asString()).isEqualTo("INR");
    }

    @Test
    void policyNumbersAreUnique() throws Exception {
        String number = "SYN-" + UUID.randomUUID();
        Map<String, String> body = Map.of("policyNumber", number, "productName", "P", "policyholderName", "H");

        assertThat(fixtures.policyManager().postJson("/api/v1/policies", body).getStatus()).isEqualTo(201);

        assertThat(fixtures.policyManager().postJson("/api/v1/policies", body).getStatus()).isEqualTo(409);
    }

    @ParameterizedTest
    @CsvSource({
        "2025-06-01, 2025-06-30",
        "2024-06-01, 2025-01-01",
        "2025-12-31, 2026-06-30",
        "2024-01-01, 2026-12-31"
    })
    void overlappingPeriodsAreRefused(String from, String to) throws Exception {
        String policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2025-01-01", "2025-12-31");

        MockHttpServletResponse response = addVersion(policy, from, to);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(fixtures.policyManager().bodyOf(response).get("code").asString()).isEqualTo("overlapping_period");
    }

    @Test
    void concurrentOverlappingVersionsCannotBothSucceed() throws Exception {
        String policy = fixtures.createPolicy();
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            attempts.add(() -> addVersion(policy, "2025-01-01", "2025-12-31").getStatus());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (Future<Integer> result : executor.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsOnlyOnce(201);
        assertThat(detail(policy).get("versions")).hasSize(1);
    }

    @Test
    void endBeforeStartIsRefused() throws Exception {
        assertThat(addVersion(fixtures.createPolicy(), "2025-12-31", "2025-01-01").getStatus()).isEqualTo(422);
    }

    @Test
    void wordingMustBeAPdfAndReuploadSupersedes() throws Exception {
        String policy = fixtures.createPolicy();
        String version = fixtures.addVersion(policy, "2025-01-01", "2025-12-31");
        String path = "/api/v1/policies/" + policy + "/versions/" + version + "/wording";
        Client manager = fixtures.policyManager();

        assertThat(manager.upload(path, "scan.png", read("claims/scanned/prescription.png")).getStatus()).isEqualTo(422);
        assertThat(manager.upload(path, "wording.pdf", read(WORDING)).getStatus()).isEqualTo(201);
        assertThat(manager.upload(path, "wording-v2.pdf", read("policies/family-floater-gold-v2.pdf")).getStatus())
                .isEqualTo(201);

        JsonNode documents = detail(policy).get("versions").get(0).get("documents");
        assertThat(ClaimFixtures.values(documents, "status")).containsExactly("SUPERSEDED", "ACTIVE");
    }

    @Test
    void aVersionOfAnotherPolicyIsNotFound() throws Exception {
        String version = fixtures.addVersion(fixtures.createPolicy(), "2025-01-01", "2025-12-31");

        MockHttpServletResponse response = fixtures.policyManager().upload(
                "/api/v1/policies/" + fixtures.createPolicy() + "/versions/" + version + "/wording", "w.pdf", read(WORDING));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @ParameterizedTest
    @CsvSource({"REVIEWER, 200", "CLAIMS_PROCESSOR, 200", "AUDITOR, 200", "POLICY_MANAGER, 200", "ADMIN, 403"})
    void readingFollowsRoles(Role role, int expected) throws Exception {
        assertThat(signedInAs(role).getPath("/api/v1/policies").getStatus()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"REVIEWER", "CLAIMS_PROCESSOR", "AUDITOR", "ADMIN"})
    void onlyPolicyManagersChangeTheRegister(Role role) throws Exception {
        MockHttpServletResponse response = signedInAs(role).postJson(
                "/api/v1/policies",
                Map.of("policyNumber", "SYN-" + UUID.randomUUID(), "productName", "P", "policyholderName", "H"));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void wordingCanBeReadByPolicyReaders() throws Exception {
        String policy = fixtures.createPolicy();
        String version = fixtures.addVersion(policy, "2025-01-01", "2025-12-31");
        Client manager = fixtures.policyManager();
        String document = manager.bodyOf(
                manager.upload("/api/v1/policies/" + policy + "/versions/" + version + "/wording", "w.pdf", read(WORDING)))
                .get("id").asString();

        assertThat(signedInAs(Role.REVIEWER).getPath("/api/v1/documents/" + document + "/pages/1").getStatus()).isEqualTo(200);
        assertThat(signedInAs(Role.ADMIN).getPath("/api/v1/documents/" + document + "/pages/1").getStatus()).isEqualTo(403);
    }
}
