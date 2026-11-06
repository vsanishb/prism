package dev.prism.core.support;

import dev.prism.core.identity.Role;
import dev.prism.core.support.IntegrationTest.Client;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Builds policies and claims through the real API, so that tests start from valid states. */
public final class ClaimFixtures {

    public static final Path TEST_DATA = Path.of("../../test-data");
    public static final String BILL = "claims/appendectomy/hospital-bill.pdf";

    private final IntegrationTest test;
    private Client policyManager;
    private Client processor;
    private String policyId;

    ClaimFixtures(IntegrationTest test) {
        this.test = test;
    }

    public static byte[] read(String relativePath) throws Exception {
        return Files.readAllBytes(TEST_DATA.resolve(relativePath));
    }

    public Client policyManager() throws Exception {
        if (policyManager == null) {
            policyManager = test.signedInAs(Role.POLICY_MANAGER);
        }
        return policyManager;
    }

    public Client processor() throws Exception {
        if (processor == null) {
            processor = test.signedInAs(Role.CLAIMS_PROCESSOR);
        }
        return processor;
    }

    public String createPolicy() throws Exception {
        Client manager = policyManager();
        MockHttpServletResponse response = manager.postJson(
                "/api/v1/policies",
                Map.of(
                        "policyNumber", "SYN-" + UUID.randomUUID(),
                        "productName", "Synthetic Test Product",
                        "policyholderName", "Synthetic Holder"));
        return manager.bodyOf(response).get("id").asString();
    }

    public String addVersion(String policy, String from, String to) throws Exception {
        Client manager = policyManager();
        MockHttpServletResponse response = manager.postJson(
                "/api/v1/policies/" + policy + "/versions",
                Map.of("effectiveFrom", from, "effectiveTo", to, "sumInsured", "500000.00"));
        return manager.bodyOf(response).get("id").asString();
    }

    /** A shared policy with versions for 2025 and 2026. */
    public String policyWithVersions() throws Exception {
        if (policyId == null) {
            policyId = createPolicy();
            addVersion(policyId, "2025-01-01", "2025-12-31");
            addVersion(policyId, "2026-01-01", "2026-12-31");
        }
        return policyId;
    }

    public Map<String, Object> claimBody(String policy) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "REIMBURSEMENT");
        body.put("policyId", policy);
        body.put("claimantName", "Synthetic Claimant");
        body.put("patientName", "Synthetic Patient");
        body.put("hospitalName", "Synthetic Hospital");
        body.put("admissionDate", "2026-03-14");
        body.put("dischargeDate", "2026-03-17");
        body.put("claimedAmount", "106000.00");
        return body;
    }

    public MockHttpServletResponse postClaim(Client client, Map<String, Object> body, String key) throws Exception {
        return client.postJsonWithHeader("/api/v1/claims", body, "Idempotency-Key", key);
    }

    public JsonNode draftClaim() throws Exception {
        MockHttpServletResponse response =
                postClaim(processor(), claimBody(policyWithVersions()), UUID.randomUUID().toString());
        return processor().bodyOf(response);
    }

    public String draftClaimId() throws Exception {
        return draftClaim().get("summary").get("id").asString();
    }

    public String uploadBill(String claimId) throws Exception {
        MockHttpServletResponse response = upload(processor(), claimId, "HOSPITAL_BILL", "bill.pdf", read(BILL));
        return processor().bodyOf(response).get("id").asString();
    }

    public MockHttpServletResponse upload(Client client, String claimId, String type, String name, byte[] content)
            throws Exception {
        return client.upload("/api/v1/claims/" + claimId + "/documents?documentType=" + type, name, content);
    }

    public String submittedClaimId() throws Exception {
        String id = draftClaimId();
        uploadBill(id);
        processor().postEmpty("/api/v1/claims/" + id + "/submission");
        return id;
    }

    public static List<String> values(JsonNode array, String field) {
        return array.valueStream().map(node -> node.get(field).asString()).toList();
    }
}
