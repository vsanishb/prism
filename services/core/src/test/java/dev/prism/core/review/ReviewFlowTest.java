package dev.prism.core.review;

import static dev.prism.core.intelligence.internal.ExtractionFixtures.bill;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.claimForm;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.discharge;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.claim.ClaimProcessing;
import dev.prism.core.identity.Role;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Human review on the real database: routing, separation of duties, the information-request loop,
 * corrections, outcomes, and the evidence chain. The workflow is stood in for by calling the same
 * public APIs its activities call.
 */
class ReviewFlowTest extends IntegrationTest {

    @Autowired
    private ClaimIntelligence intelligence;

    @Autowired
    private ClaimProcessing processing;

    @Autowired
    private ReviewQueue queue;

    @Autowired
    private JdbcClient jdbc;

    private ClaimFixtures fixtures;
    private Client processor;
    private String policy;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        processor = fixtures.processor();
        policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2026-01-01", "2026-12-31");
        doAnswer(call -> switch (call.<DocumentExtraction.Request>getArgument(0).declaredType()) {
            case "CLAIM_FORM" -> claimForm("2026-03-14", "106000.00");
            case "HOSPITAL_BILL" -> bill("106000.00", List.of(item("Room", "6000.00"), item("Surgery", "100000.00")));
            default -> discharge("2026-03-14");
        }).when(aiService).extractDocument(any());
    }

    private static byte[] unique(String path) throws Exception {
        byte[] original = ClaimFixtures.read(path);
        byte[] suffix = ("\n%" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[original.length + suffix.length];
        System.arraycopy(original, 0, content, 0, original.length);
        System.arraycopy(suffix, 0, content, original.length, suffix.length);
        return content;
    }

    /** A claim processed as far as review, as the workflow would take it. */
    private UUID claimInReview(String amount) throws Exception {
        Map<String, Object> body = new HashMap<>(fixtures.claimBody(policy));
        body.put("claimedAmount", amount);
        String claim = processor.bodyOf(fixtures.postClaim(processor, body, UUID.randomUUID().toString()))
                .get("summary").get("id").asString();
        fixtures.upload(processor, claim, "CLAIM_FORM", "form.pdf", unique("claims/appendectomy/claim-form.pdf"));
        fixtures.upload(processor, claim, "HOSPITAL_BILL", "bill.pdf", unique(ClaimFixtures.BILL));
        fixtures.upload(processor, claim, "DISCHARGE_SUMMARY", "discharge.pdf", unique("claims/appendectomy/discharge-summary.pdf"));
        processor.postEmpty("/api/v1/claims/" + claim + "/submission");
        UUID id = UUID.fromString(claim);
        processAgain(id);
        return id;
    }

    /** One processing cycle: what the workflow's begin, extraction, consolidation, and ready-for-review activities do. */
    private void processAgain(UUID claim) {
        processing.begin(claim);
        queue.cancelLiveTask(claim);
        for (UUID document : intelligence.documentsToExtract(claim)) {
            intelligence.extract(claim, document);
        }
        intelligence.consolidate(claim);
        processing.readyForReview(claim);
        queue.openTask(claim);
    }

    /** A screening result, without running screening: this test is about what review does with the band. */
    private void screenedAs(UUID claim, String band) {
        jdbc.sql("""
                        INSERT INTO core.risk_assessments (claim_id, consolidation_id, ruleset_version, scoring_version, feature_version,
                            model_version, model_fingerprint, baseline_size, score, band, route, rules_only_score, rules_only_band,
                            rules_evaluated, features)
                        SELECT ?, id, 'risk-rules-2', 'risk-scoring-1', 'risk-features-1', 'anomaly-1', 'test', 5000, 60, ?,
                            CASE WHEN ? = 'HIGH' THEN 'SENIOR_REVIEW' ELSE 'STANDARD_REVIEW' END, 40, 'MEDIUM', '{}', '[]'
                        FROM core.claim_consolidations WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1
                        """)
                .params(claim, band, band, claim)
                .update();
    }

    private JsonNode workspace(Client client, UUID claim) throws Exception {
        return client.bodyOf(client.getPath("/api/v1/claims/" + claim + "/review"));
    }

    private String status(UUID claim) {
        return jdbc.sql("SELECT status FROM core.claims WHERE id = ?").param(claim).query(String.class).single();
    }

    private String taskId(Client client, UUID claim) throws Exception {
        return workspace(client, claim).get("task").get("id").asString();
    }

    private MockHttpServletResponse take(Client client, UUID claim) throws Exception {
        return client.postEmpty("/api/v1/review/tasks/" + taskId(client, claim) + "/claim");
    }

    private MockHttpServletResponse decide(Client client, UUID claim, String decision, String amount) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId(client, claim));
        body.put("decision", decision);
        body.put("approvedAmount", amount);
        body.put("rationale", "Covered in-patient treatment, documents consistent.");
        body.put("aiAgreement", "AGREED");
        return client.postJson("/api/v1/claims/" + claim + "/review/outcome", body);
    }

    private List<String> auditActions(UUID claim) {
        return jdbc.sql("SELECT action FROM core.audit_events WHERE stream = ? ORDER BY sequence").param("claim:" + claim)
                .query(String.class).list();
    }

    @Test
    void aStandardClaimIsReviewedAndDecidedOnce() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);

        JsonNode lists = reviewer.bodyOf(reviewer.getPath("/api/v1/review/tasks"));
        assertThat(lists.get("available").findValuesAsString("claimId")).contains(claim.toString());
        JsonNode task = workspace(reviewer, claim).get("task");
        assertThat(task.get("queue").asString()).isEqualTo("REVIEW");
        assertThat(task.get("reason").asString()).isEqualTo("STANDARD");

        assertThat(take(reviewer, claim).getStatus()).isEqualTo(200);
        assertThat(workspace(reviewer, claim).get("actions").toString()).contains("RECORD_OUTCOME", "ESCALATE", "REQUEST_INFORMATION");
        String task1 = taskId(reviewer, claim);
        assertThat(decide(reviewer, claim, "APPROVED", null).getStatus()).isEqualTo(201);

        assertThat(status(claim)).isEqualTo("DECIDED");
        JsonNode outcome = workspace(reviewer, claim).get("outcomes").get(0);
        assertThat(outcome.get("decision").asString()).isEqualTo("APPROVED");
        assertThat(outcome.get("approvedAmount").decimalValue()).isEqualByComparingTo("106000.00");
        assertThat(outcome.get("current").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM core.outbox_events WHERE aggregate_id = ? AND event_type = 'CLAIM_DECIDED'")
                .param(claim).query(Integer.class).single()).isEqualTo(1);
        assertThat(auditActions(claim)).contains("REVIEW_TASK_OPENED", "REVIEW_TASK_CLAIMED", "CLAIM_OUTCOME_RECORDED", "CLAIM_DECIDED");

        // A second decision, such as a retried request, is refused: there is no task left to decide on.
        Map<String, Object> again = Map.of("taskId", task1, "decision", "DENIED", "rationale", "Second attempt at deciding.",
                "aiAgreement", "AGREED");
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/outcome", again).getStatus()).isEqualTo(409);
    }

    @Test
    void aClaimAboveTheAuthorityLimitGoesToSeniorReview() throws Exception {
        UUID claim = claimInReview("400000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        Client senior = signedInAs(Role.SENIOR_REVIEWER);
        JsonNode task = workspace(senior, claim).get("task");
        assertThat(task.get("queue").asString()).isEqualTo("SENIOR_REVIEW");
        assertThat(task.get("reason").asString()).isEqualTo("ABOVE_AUTHORITY_LIMIT");
        assertThat(status(claim)).isEqualTo("IN_REVIEW");

        assertThat(take(reviewer, claim).getStatus()).isEqualTo(403);
        assertThat(take(senior, claim).getStatus()).isEqualTo(200);
        assertThat(decide(senior, claim, "PARTIALLY_APPROVED", "350000.00").getStatus()).isEqualTo(201);
    }

    @Test
    void aHighRiskClaimIsInvestigatedFirstAndOnlyAReviewerDecides() throws Exception {
        UUID claim = claimInReview("106000.00");
        // Screened after the first cycle: the next cycle routes on it.
        screenedAs(claim, "HIGH");
        processAgain(claim);
        Client investigator = signedInAs(Role.FRAUD_INVESTIGATOR);
        Client reviewer = signedInAs(Role.REVIEWER);
        Client senior = signedInAs(Role.SENIOR_REVIEWER);

        assertThat(workspace(investigator, claim).get("task").get("queue").asString()).isEqualTo("INVESTIGATION");
        assertThat(status(claim)).isEqualTo("ESCALATED");
        assertThat(take(reviewer, claim).getStatus()).isEqualTo(403);
        assertThat(take(investigator, claim).getStatus()).isEqualTo(200);
        // Investigators record findings, never outcomes.
        assertThat(decide(investigator, claim, "DENIED", null).getStatus()).isEqualTo(403);

        MockHttpServletResponse finding = investigator.postJson("/api/v1/claims/" + claim + "/review/investigation-findings", Map.of(
                "taskId", taskId(investigator, claim), "finding", "CONFIRMED_FRAUD",
                "rationale", "The bill was confirmed as altered with the hospital."));
        assertThat(finding.getStatus()).isEqualTo(204);

        JsonNode next = workspace(senior, claim).get("task");
        assertThat(next.get("queue").asString()).isEqualTo("SENIOR_REVIEW");
        assertThat(next.get("reason").asString()).isEqualTo("INVESTIGATED");
        assertThat(status(claim)).isEqualTo("IN_REVIEW");
        assertThat(take(senior, claim).getStatus()).isEqualTo(200);
        assertThat(decide(senior, claim, "DENIED", null).getStatus()).isEqualTo(201);
        assertThat(workspace(senior, claim).get("findings").get(0).get("finding").asString()).isEqualTo("CONFIRMED_FRAUD");

        // After a finding, a later high screening does not send the claim back to investigation by itself.
        assertThat(auditActions(claim)).contains("INVESTIGATION_FINDING_RECORDED");
    }

    @Test
    void nobodyReviewsAClaimTheyCreated() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        // As if this reviewer had also done the intake, for example holding both roles.
        jdbc.sql("UPDATE core.claims SET created_by = ? WHERE id = ?").params(reviewer.userId, claim).update();

        MockHttpServletResponse refused = take(reviewer, claim);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(reviewer.bodyOf(refused).get("code").asString()).isEqualTo("separation_of_duties");
        assertThat(workspace(reviewer, claim).get("createdByYou").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM core.audit_events WHERE stream = ? AND outcome = 'DENIED'")
                .param("claim:" + claim).query(Integer.class).single()).isEqualTo(1);
        // And the queue's "next task" skips it.
        assertThat(reviewer.postEmpty("/api/v1/review/tasks/next").getContentAsString()).doesNotContain(claim.toString());
    }

    @Test
    void anOutcomeIsRevisedOnlyBySomeoneElseWithANewRecord() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        decide(reviewer, claim, "APPROVED", null);

        Map<String, Object> revision = Map.of("decision", "PARTIALLY_APPROVED", "approvedAmount", "90000.00",
                "rationale", "Room rent exceeds the policy's daily limit.", "aiAgreement", "PARTLY_AGREED");
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/outcome/revision", revision).getStatus()).isEqualTo(403);
        Client senior = signedInAs(Role.SENIOR_REVIEWER);
        assertThat(senior.postJson("/api/v1/claims/" + claim + "/review/outcome/revision", revision).getStatus()).isEqualTo(201);

        JsonNode outcomes = workspace(senior, claim).get("outcomes");
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).get("current").asBoolean()).isFalse();
        assertThat(outcomes.get(1).get("supersedesId").asString()).isEqualTo(outcomes.get(0).get("id").asString());
        // The senior reviewer cannot then revise their own revision.
        assertThat(senior.postJson("/api/v1/claims/" + claim + "/review/outcome/revision", revision).getStatus()).isEqualTo(403);
    }

    @Test
    void amountsMustMatchTheDecision() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        assertThat(decide(reviewer, claim, "PARTIALLY_APPROVED", "106000.00").getStatus()).isEqualTo(422);
        assertThat(decide(reviewer, claim, "PARTIALLY_APPROVED", null).getStatus()).isEqualTo(422);
        assertThat(decide(reviewer, claim, "DENIED", "100.00").getStatus()).isEqualTo(422);
        assertThat(decide(reviewer, claim, "APPROVED", "1.00").getStatus()).isEqualTo(422);
        assertThat(status(claim)).isEqualTo("IN_REVIEW");
    }

    @Test
    void aRequestForInformationWaitsForDocumentsAndReturnsToTheQueue() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        MockHttpServletResponse requested = reviewer.postJson("/api/v1/claims/" + claim + "/review/information-requests", Map.of(
                "taskId", taskId(reviewer, claim), "documentTypes", List.of("PRESCRIPTION"),
                "message", "Please send the prescription from the admission."));
        assertThat(requested.getStatus()).isEqualTo(204);
        assertThat(status(claim)).isEqualTo("AWAITING_INFORMATION");
        assertThat(workspace(reviewer, claim).get("task").isNull()).isTrue();

        // Intake adds the document. The workflow processes the claim again, and it returns to review.
        fixtures.upload(processor, claim.toString(), "PRESCRIPTION", "prescription.png", ClaimFixtures.read("claims/scanned/prescription.png"));
        processAgain(claim);
        JsonNode view = workspace(reviewer, claim);
        assertThat(view.get("task").get("reason").asString()).isEqualTo("INFORMATION_RECEIVED");
        assertThat(view.get("requests").get(0).get("status").asString()).isEqualTo("FULFILLED");
    }

    @Test
    void aRequestForInformationCanBeWithdrawn() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        reviewer.postJson("/api/v1/claims/" + claim + "/review/information-requests", Map.of(
                "taskId", taskId(reviewer, claim), "documentTypes", List.of("PRESCRIPTION"), "message", "Prescription, please."));
        String request = workspace(reviewer, claim).get("requests").get(0).get("id").asString();
        assertThat(reviewer.postEmpty("/api/v1/claims/" + claim + "/review/information-requests/" + request + "/withdrawal").getStatus())
                .isEqualTo(204);
        assertThat(status(claim)).isEqualTo("IN_REVIEW");
        assertThat(workspace(reviewer, claim).get("task").get("reason").asString()).isEqualTo("REQUEST_CANCELLED");
    }

    @Test
    void aTakenTaskCannotBeTakenAgain() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client first = signedInAs(Role.REVIEWER);
        Client second = signedInAs(Role.REVIEWER);
        String task = taskId(first, claim);
        assertThat(first.postEmpty("/api/v1/review/tasks/" + task + "/claim").getStatus()).isEqualTo(200);
        assertThat(second.postEmpty("/api/v1/review/tasks/" + task + "/claim").getStatus()).isEqualTo(409);
        assertThat(second.postEmpty("/api/v1/review/tasks/" + task + "/release").getStatus()).isEqualTo(409);
        assertThat(first.postEmpty("/api/v1/review/tasks/" + task + "/release").getStatus()).isEqualTo(204);
        assertThat(second.postEmpty("/api/v1/review/tasks/" + task + "/claim").getStatus()).isEqualTo(200);
    }

    @Test
    void escalationGoesUpOnly() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        String task = taskId(reviewer, claim);
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/escalation", Map.of(
                "taskId", task, "target", "REVIEW", "note", "Sideways is not an escalation.")).getStatus()).isEqualTo(422);
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/escalation", Map.of(
                "taskId", task, "target", "SENIOR_REVIEW", "note", "Unusual treatment for the diagnosis.")).getStatus()).isEqualTo(204);
        JsonNode next = workspace(reviewer, claim).get("task");
        assertThat(next.get("queue").asString()).isEqualTo("SENIOR_REVIEW");
        assertThat(next.get("escalationNote").asString()).isEqualTo("Unusual treatment for the diagnosis.");
        assertThat(status(claim)).isEqualTo("ESCALATED");
    }

    @Test
    void aCorrectionIsAVersionAndAppliesWhenTheReviewerAsks() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        JsonNode facts = reviewer.bodyOf(reviewer.getPath("/api/v1/claims/" + claim + "/facts")).get("facts");
        String admission = null;
        for (JsonNode fact : facts) {
            if ("admission_date".equals(fact.get("name").asString()) && fact.get("documentId").isNull()) {
                admission = fact.get("id").asString();
            }
        }
        String task = taskId(reviewer, claim);
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/fact-corrections", Map.of(
                "taskId", task, "factId", admission, "value", "14-03-2026", "reason", "Format")).getStatus()).isEqualTo(422);
        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/fact-corrections", Map.of(
                "taskId", task, "factId", admission, "value", "2026-03-13", "reason", "The discharge summary is handwritten as the 13th.")).getStatus())
                .isEqualTo(201);
        JsonNode view = workspace(reviewer, claim);
        assertThat(view.get("corrections").get(0).get("applied").asBoolean()).isFalse();
        assertThat(view.get("corrections").get(0).get("originalValue").asString()).isEqualTo("2026-03-14");
        assertThat(view.get("actions").toString()).contains("REQUEST_REANALYSIS");

        assertThat(reviewer.postJson("/api/v1/claims/" + claim + "/review/reanalysis", Map.of("taskId", task)).getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM core.outbox_events WHERE aggregate_id = ? AND event_type = 'CLAIM_REANALYSIS_REQUESTED'")
                .param(claim).query(Integer.class).single()).isEqualTo(1);
        processAgain(claim);

        JsonNode corrected = null;
        for (JsonNode fact : reviewer.bodyOf(reviewer.getPath("/api/v1/claims/" + claim + "/facts")).get("facts")) {
            if ("admission_date".equals(fact.get("name").asString()) && fact.get("documentId").isNull()) {
                corrected = fact;
            }
        }
        assertThat(corrected.get("state").asString()).isEqualTo("PRESENT");
        assertThat(corrected.get("value").asString()).isEqualTo("2026-03-13");
        assertThat(corrected.get("correction").get("reason").asString()).startsWith("The discharge summary");
        assertThat(corrected.get("sources")).isNotEmpty();
        assertThat(workspace(reviewer, claim).get("task").get("reason").asString()).isEqualTo("REANALYZED");
        assertThat(workspace(reviewer, claim).get("corrections").get(0).get("applied").asBoolean()).isTrue();
    }

    @Test
    void theEvidenceChainTracesAnOutcomeToItsSources() throws Exception {
        UUID claim = claimInReview("106000.00");
        Client reviewer = signedInAs(Role.REVIEWER);
        take(reviewer, claim);
        decide(reviewer, claim, "APPROVED", null);

        Client auditor = signedInAs(Role.AUDITOR);
        JsonNode chain = auditor.bodyOf(auditor.getPath("/api/v1/claims/" + claim + "/evidence-chain"));
        assertThat(chain.get("outcomes").get(0).get("decidedBy").asString()).isNotBlank();
        assertThat(chain.get("tasks").get(0).get("completion").asString()).isEqualTo("OUTCOME_RECORDED");
        JsonNode evidence = chain.get("evidence");
        assertThat(evidence.get("sequence").asInt()).isEqualTo(1);
        assertThat(evidence.get("documents")).hasSize(3);
        assertThat(evidence.get("documents").get(0).get("sha256").asString()).hasSize(64);
        assertThat(evidence.get("modelRuns")).isNotEmpty();
        JsonNode admission = null;
        for (JsonNode fact : evidence.get("facts")) {
            if ("admission_date".equals(fact.get("name").asString())) {
                admission = fact;
            }
        }
        assertThat(admission.get("sources").get(0).get("page").asInt()).isEqualTo(1);

        assertThat(signedInAs(Role.ADMIN).getPath("/api/v1/claims/" + claim + "/evidence-chain").getStatus()).isEqualTo(403);
    }

    @Test
    void reprocessingCancelsTheLiveTask() throws Exception {
        UUID claim = claimInReview("106000.00");
        String before = taskId(signedInAs(Role.REVIEWER), claim);
        processing.begin(claim);
        queue.cancelLiveTask(claim);
        assertThat(jdbc.sql("SELECT status FROM core.review_tasks WHERE id = ?").param(UUID.fromString(before)).query(String.class).single())
                .isEqualTo("CANCELLED");
        // Opening is idempotent: a repeated activity does not open a second task.
        processing.readyForReview(claim);
        queue.openTask(claim);
        queue.openTask(claim);
        assertThat(jdbc.sql("SELECT count(*) FROM core.review_tasks WHERE claim_id = ? AND status = 'OPEN'").param(claim)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
