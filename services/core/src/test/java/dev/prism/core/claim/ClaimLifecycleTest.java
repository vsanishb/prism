package dev.prism.core.claim;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

class ClaimLifecycleTest extends IntegrationTest {

    private ClaimFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = claimFixtures();
    }

    private JsonNode detail(Client client, String claimId) throws Exception {
        return client.bodyOf(client.getPath("/api/v1/claims/" + claimId));
    }

    private MockHttpServletResponse update(String claimId, Map<String, Object> claim, long version) throws Exception {
        return fixtures.processor().send(MockMvcRequestBuilders.put("/api/v1/claims/" + claimId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("claim", claim, "version", version))));
    }

    @Test
    void createsADraftWithANumberAndNoFixedVersion() throws Exception {
        JsonNode claim = fixtures.draftClaim();

        assertThat(claim.get("summary").get("status").asString()).isEqualTo("DRAFT");
        assertThat(claim.get("summary").get("claimNumber").asString()).matches("CLM-\\d{4}-\\d{6}");
        assertThat(claim.get("summary").get("currency").asString()).isEqualTo("INR");
        assertThat(claim.get("policyVersionFixed").asBoolean()).isFalse();
    }

    @Test
    void repeatingAnIdempotencyKeyReturnsTheSameClaim() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = fixtures.claimBody(fixtures.policyWithVersions());

        JsonNode first = fixtures.processor().bodyOf(fixtures.postClaim(fixtures.processor(), body, key));
        JsonNode second = fixtures.processor().bodyOf(fixtures.postClaim(fixtures.processor(), body, key));

        assertThat(second.get("summary").get("id").asString()).isEqualTo(first.get("summary").get("id").asString());
    }

    @Test
    void creationRequiresAnIdempotencyKey() throws Exception {
        MockHttpServletResponse response =
                fixtures.processor().postJson("/api/v1/claims", fixtures.claimBody(fixtures.policyWithVersions()));

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({
        "claimedAmount, 0, 400",
        "claimedAmount, -5, 400",
        "claimedAmount, 10.123, 400",
        "patientName, '', 400",
        "dischargeDate, 2026-03-13, 422",
        "admissionDate, 2099-01-01, 422",
        "policyId, 00000000-0000-0000-0000-000000000001, 422"
    })
    void rejectsInvalidDetails(String field, String value, int expectedStatus) throws Exception {
        Map<String, Object> body = fixtures.claimBody(fixtures.policyWithVersions());
        body.put(field, value);
        if ("admissionDate".equals(field)) {
            body.put("dischargeDate", value);
        }

        assertThat(fixtures.postClaim(fixtures.processor(), body, UUID.randomUUID().toString()).getStatus())
                .isEqualTo(expectedStatus);
    }

    @Test
    void draftCanBeEditedAndStaleEditsAreRefused() throws Exception {
        JsonNode claim = fixtures.draftClaim();
        String id = claim.get("summary").get("id").asString();
        long version = claim.get("version").asLong();
        Map<String, Object> changed = fixtures.claimBody(fixtures.policyWithVersions());
        changed.put("patientName", "Anil Kumar Desai");

        assertThat(update(id, changed, version).getStatus()).isEqualTo(200);
        assertThat(detail(fixtures.processor(), id).get("summary").get("patientName").asString())
                .isEqualTo("Anil Kumar Desai");

        MockHttpServletResponse stale = update(id, changed, version);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(fixtures.processor().bodyOf(stale).get("code").asString()).isEqualTo("concurrent_change");
    }

    @Test
    void submissionNeedsADocument() throws Exception {
        String id = fixtures.draftClaimId();
        assertThat(detail(fixtures.processor(), id).get("submissionIssues").toString()).contains("document");

        MockHttpServletResponse response = fixtures.processor().postEmpty("/api/v1/claims/" + id + "/submission");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(fixtures.processor().bodyOf(response).get("code").asString()).isEqualTo("not_ready");
    }

    @Test
    void submissionNeedsAVersionCoveringTheAdmissionDate() throws Exception {
        String policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2020-01-01", "2020-12-31");
        Map<String, Object> body = fixtures.claimBody(policy);
        Client processor = fixtures.processor();
        String id = processor.bodyOf(fixtures.postClaim(processor, body, UUID.randomUUID().toString()))
                .get("summary").get("id").asString();
        fixtures.uploadBill(id);

        MockHttpServletResponse response = processor.postEmpty("/api/v1/claims/" + id + "/submission");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("No version of this policy covers the admission date.");
    }

    @Test
    void submissionFixesTheVersionCoveringTheAdmissionDate() throws Exception {
        String policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2025-01-01", "2025-12-31");
        String expected = fixtures.addVersion(policy, "2026-01-01", "2026-12-31");
        Client processor = fixtures.processor();
        String id = processor.bodyOf(fixtures.postClaim(processor, fixtures.claimBody(policy), UUID.randomUUID().toString()))
                .get("summary").get("id").asString();
        fixtures.uploadBill(id);

        JsonNode submitted = processor.bodyOf(processor.postEmpty("/api/v1/claims/" + id + "/submission"));

        assertThat(submitted.get("summary").get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("policyVersionFixed").asBoolean()).isTrue();
        assertThat(submitted.get("policyVersion").get("id").asString()).isEqualTo(expected);
    }

    @Test
    void submittedClaimsCannotBeEdited() throws Exception {
        String id = fixtures.submittedClaimId();

        MockHttpServletResponse response =
                update(id, fixtures.claimBody(fixtures.policyWithVersions()), detail(fixtures.processor(), id).get("version").asLong());

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(fixtures.processor().bodyOf(response).get("code").asString()).isEqualTo("not_editable");
    }

    @Test
    void submittingTwiceIsHarmless() throws Exception {
        String id = fixtures.submittedClaimId();

        assertThat(fixtures.processor().postEmpty("/api/v1/claims/" + id + "/submission").getStatus()).isEqualTo(200);

        JsonNode timeline = fixtures.processor().bodyOf(fixtures.processor().getPath("/api/v1/claims/" + id + "/timeline"));
        assertThat(ClaimFixtures.values(timeline, "action").stream().filter("CLAIM_SUBMITTED"::equals)).hasSize(1);
    }

    @Test
    void concurrentSubmissionsRecordOneSubmission() throws Exception {
        String id = fixtures.draftClaimId();
        fixtures.uploadBill(id);
        Client processor = fixtures.processor();
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            attempts.add(() -> processor.postEmpty("/api/v1/claims/" + id + "/submission").getStatus());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (Future<Integer> result : executor.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsOnly(200);
        JsonNode timeline = processor.bodyOf(processor.getPath("/api/v1/claims/" + id + "/timeline"));
        assertThat(ClaimFixtures.values(timeline, "action").stream().filter("CLAIM_SUBMITTED"::equals)).hasSize(1);
    }

    @Test
    void withdrawalEndsTheClaim() throws Exception {
        String id = fixtures.submittedClaimId();
        Client processor = fixtures.processor();
        String path = "/api/v1/claims/" + id + "/withdrawal";

        assertThat(processor.postJson(path, Map.of("reason", "CLAIMANT_REQUEST")).getStatus()).isEqualTo(200);

        JsonNode claim = detail(processor, id);
        assertThat(claim.get("summary").get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(claim.get("availableActions")).isEmpty();
        assertThat(processor.postJson(path, Map.of("reason", "CLAIMANT_REQUEST")).getStatus()).isEqualTo(409);
    }

    @Test
    void withdrawalNeedsAKnownReason() throws Exception {
        String path = "/api/v1/claims/" + fixtures.draftClaimId() + "/withdrawal";

        assertThat(fixtures.processor().postJson(path, Map.of("reason", "BECAUSE")).getStatus()).isEqualTo(400);
    }

    @Test
    void timelineShowsBusinessEventsWithoutPageViews() throws Exception {
        String id = fixtures.draftClaimId();
        String document = fixtures.uploadBill(id);
        Client processor = fixtures.processor();
        processor.getPath("/api/v1/documents/" + document + "/pages/1");
        processor.postEmpty("/api/v1/claims/" + id + "/submission");

        List<String> actions = ClaimFixtures.values(
                processor.bodyOf(processor.getPath("/api/v1/claims/" + id + "/timeline")), "action");

        assertThat(actions).containsExactly("CLAIM_SUBMITTED", "DOCUMENT_UPLOADED", "CLAIM_CREATED");
    }

    @Test
    void draftsAreInvisibleOutsideIntake() throws Exception {
        String draft = fixtures.draftClaimId();
        String submitted = fixtures.submittedClaimId();
        Client reviewer = signedInAs(Role.REVIEWER);

        assertThat(reviewer.getPath("/api/v1/claims/" + draft).getStatus()).isEqualTo(404);
        assertThat(reviewer.getPath("/api/v1/claims/" + draft + "/timeline").getStatus()).isEqualTo(404);
        assertThat(reviewer.getPath("/api/v1/claims/" + submitted).getStatus()).isEqualTo(200);
        JsonNode list = reviewer.bodyOf(reviewer.getPath("/api/v1/claims?limit=100"));
        assertThat(ClaimFixtures.values(list.get("items"), "status")).doesNotContain("DRAFT");
    }

    @Test
    void reviewersHaveNoActionsOnSubmittedClaims() throws Exception {
        String id = fixtures.submittedClaimId();
        Client reviewer = signedInAs(Role.REVIEWER);

        assertThat(detail(reviewer, id).get("availableActions")).isEmpty();
        assertThat(reviewer.postJson("/api/v1/claims/" + id + "/withdrawal", Map.of("reason", "CLAIMANT_REQUEST")).getStatus())
                .isEqualTo(403);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN, 403", "POLICY_MANAGER, 403", "AUDITOR, 200", "REVIEWER, 200", "FRAUD_INVESTIGATOR, 200"})
    void claimsListAccessFollowsRoles(Role role, int expected) throws Exception {
        assertThat(signedInAs(role).getPath("/api/v1/claims").getStatus()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN", "POLICY_MANAGER", "AUDITOR", "REVIEWER", "SENIOR_REVIEWER", "FRAUD_INVESTIGATOR"})
    void onlyProcessorsCreateClaims(Role role) throws Exception {
        MockHttpServletResponse response = fixtures.postClaim(
                signedInAs(role), fixtures.claimBody(fixtures.policyWithVersions()), UUID.randomUUID().toString());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void listIsFilteredSearchedAndPaginated() throws Exception {
        String submitted = fixtures.submittedClaimId();
        fixtures.draftClaimId();
        fixtures.draftClaimId();
        Client processor = fixtures.processor();
        String number = detail(processor, submitted).get("summary").get("claimNumber").asString();

        JsonNode byNumber = processor.bodyOf(processor.getPath("/api/v1/claims?search=" + number.toLowerCase()));
        assertThat(ClaimFixtures.values(byNumber.get("items"), "id")).containsExactly(submitted);

        JsonNode drafts = processor.bodyOf(processor.getPath("/api/v1/claims?status=DRAFT&limit=100"));
        assertThat(ClaimFixtures.values(drafts.get("items"), "status")).containsOnly("DRAFT");

        JsonNode first = processor.bodyOf(processor.getPath("/api/v1/claims?limit=2"));
        JsonNode second = processor.bodyOf(processor.getPath("/api/v1/claims?limit=2&cursor=" + first.get("nextCursor").asString()));
        assertThat(ClaimFixtures.values(second.get("items"), "id"))
                .doesNotContainAnyElementsOf(ClaimFixtures.values(first.get("items"), "id"));
    }
}
