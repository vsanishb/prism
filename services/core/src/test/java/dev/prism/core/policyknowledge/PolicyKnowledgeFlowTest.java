package dev.prism.core.policyknowledge;

import static dev.prism.core.policyknowledge.internal.PolicyFixtures.goldWording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.identity.Role;
import dev.prism.core.policyknowledge.PolicyIngestion.PageRange;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Upload, ingestion, review, and publication, on the real database. Only the AI service is mocked. */
class PolicyKnowledgeFlowTest extends IntegrationTest {

    private static final String WORDING = "policies/family-floater-gold-v1.pdf";

    @Autowired
    private PolicyIngestion ingestion;

    @Autowired
    private JdbcClient jdbc;

    private ClaimFixtures fixtures;
    private Client uploader;
    private String policy;
    private String version;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        uploader = fixtures.policyManager();
        policy = fixtures.createPolicy();
        version = fixtures.addVersion(policy, "2025-04-01", "2026-03-31");
    }

    private String base() {
        return "/api/v1/policies/" + policy + "/versions/" + version + "/knowledge";
    }

    private UUID uploadWording() throws Exception {
        MockHttpServletResponse response = uploader.upload(
                "/api/v1/policies/" + policy + "/versions/" + version + "/wording", "wording.pdf", ClaimFixtures.read(WORDING));
        assertThat(response.getStatus()).isEqualTo(201);
        return UUID.fromString(uploader.bodyOf(response).get("id").asString());
    }

    /** What the workflow does, step by step. */
    private UUID ingest() throws Exception {
        UUID document = uploadWording();
        when(aiService.extractPolicyPages(any())).thenReturn(goldWording());
        assertThat(ingestion.begin(document)).isTrue();
        for (PageRange range : ingestion.pendingBatches(document)) {
            ingestion.extractBatch(document, range);
        }
        ingestion.assemble(document);
        return document;
    }

    private JsonNode view(Client client) throws Exception {
        return client.bodyOf(client.getPath(base()));
    }

    private List<String> criticalValueIds(JsonNode view) {
        return view.get("provisions").valueStream()
                .flatMap(provision -> provision.get("values").valueStream())
                .filter(value -> value.get("critical").asBoolean())
                .map(value -> value.get("id").asString())
                .toList();
    }

    private MockHttpServletResponse review(Client client, String valueId, Map<String, Object> body) throws Exception {
        return client.postJson(base() + "/values/" + valueId + "/review", body);
    }

    private void confirmAll(Client client) throws Exception {
        for (String id : criticalValueIds(view(client))) {
            assertThat(review(client, id, Map.of("decision", "CONFIRMED")).getStatus()).isEqualTo(204);
        }
    }

    private String status() throws Exception {
        return view(uploader).get("knowledgeStatus").asString();
    }

    @Test
    void uploadingAWordingStartsIngestionInTheSameTransaction() throws Exception {
        UUID document = uploadWording();

        assertThat(status()).isEqualTo("PROCESSING");
        assertThat(jdbc.sql("SELECT event_type FROM core.outbox_events WHERE aggregate_id = ?")
                        .param(document)
                        .query(String.class)
                        .list())
                .containsExactly("POLICY_WORDING_ATTACHED");
    }

    @Test
    void anIngestedWordingOpensForReviewWithProvisionsValuesAndRelations() throws Exception {
        ingest();

        JsonNode view = view(uploader);
        assertThat(view.get("knowledgeStatus").asString()).isEqualTo("IN_REVIEW");
        assertThat(ClaimFixtures.values(view.get("provisions"), "type")).contains("SCHEDULE", "DEFINITION", "BENEFIT", "EXCLUSION");
        assertThat(criticalValueIds(view)).hasSize(4);
        assertThat(view.get("relations").valueStream().map(relation -> relation.get("targetKey").asString())).contains("1.1", "3.2");
        assertThat(view.get("batches").get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(view.get("blockers").get(0).get("code").asString()).isEqualTo("VALUES_NOT_REVIEWED");
    }

    @Test
    void ingestionIsIdempotent() throws Exception {
        UUID document = ingest();

        ingestion.extractBatch(document, new PageRange(1, 2));
        ingestion.assemble(document);

        verify(aiService, times(1)).extractPolicyPages(any());
        assertThat(jdbc.sql("SELECT count(*) FROM core.policy_provisions p JOIN core.policy_ingestions i ON i.id = p.ingestion_id "
                                + "WHERE i.document_id = ?")
                        .param(document)
                        .query(Long.class)
                        .single())
                .isEqualTo(6);
    }

    @Test
    void theUploaderCannotPublishButAnotherManagerCan() throws Exception {
        ingest();
        confirmAll(uploader);

        MockHttpServletResponse refused = uploader.postEmpty(base() + "/publication");
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(uploader.bodyOf(refused).get("detail").asString()).contains("uploaded the wording");

        Client approver = signedInAs(Role.POLICY_MANAGER);
        assertThat(approver.postEmpty(base() + "/publication").getStatus()).isEqualTo(204);
        JsonNode published = view(approver);
        assertThat(published.get("knowledgeStatus").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedAt").isNull()).isFalse();
    }

    @Test
    void publishingNeedsEveryCriticalValueReviewed() throws Exception {
        ingest();
        Client approver = signedInAs(Role.POLICY_MANAGER);
        String first = criticalValueIds(view(approver)).getFirst();
        review(approver, first, Map.of("decision", "CONFIRMED"));

        MockHttpServletResponse refused = approver.postEmpty(base() + "/publication");

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(approver.bodyOf(refused).get("detail").asString()).contains("3 critical values have not been");
    }

    @Test
    void aCorrectionNeedsANoteAndAValidNumber() throws Exception {
        ingest();
        String value = criticalValueIds(view(uploader)).getFirst();

        assertThat(review(uploader, value, Map.of("decision", "CORRECTED", "correctedValue", "450000")).getStatus()).isEqualTo(422);
        assertThat(review(uploader, value, Map.of("decision", "CORRECTED", "correctedValue", "abc", "note", "x")).getStatus())
                .isEqualTo(422);
        assertThat(review(uploader, value, Map.of("decision", "MAYBE")).getStatus()).isEqualTo(422);
        assertThat(review(uploader, value, Map.of("decision", "CORRECTED", "correctedValue", "4,00,000", "note", "Read from page 1"))
                        .getStatus())
                .isEqualTo(204);
    }

    @Test
    void aCorrectionMustPassTheSameRulesBeforePublishing() throws Exception {
        ingest();
        Client approver = signedInAs(Role.POLICY_MANAGER);
        confirmAll(approver);
        String sumInsured = view(approver).get("provisions").valueStream()
                .filter(provision -> provision.get("type").asString().equals("SCHEDULE"))
                .findFirst().orElseThrow().get("values").get(0).get("id").asString();
        review(approver, sumInsured, Map.of("decision", "CORRECTED", "correctedValue", "750000", "note", "Mistyped"));

        MockHttpServletResponse refused = approver.postEmpty(base() + "/publication");

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(approver.bodyOf(refused).get("detail").asString()).contains("breaks a check");
    }

    @Test
    void aPublishedVersionIsImmutable() throws Exception {
        ingest();
        Client approver = signedInAs(Role.POLICY_MANAGER);
        confirmAll(approver);
        approver.postEmpty(base() + "/publication");
        String value = criticalValueIds(view(approver)).getFirst();

        assertThat(review(approver, value, Map.of("decision", "CONFIRMED")).getStatus()).isEqualTo(409);
        assertThat(uploader.upload(
                                "/api/v1/policies/" + policy + "/versions/" + version + "/wording", "again.pdf",
                                ClaimFixtures.read("policies/family-floater-gold-v2.pdf"))
                        .getStatus())
                .isEqualTo(409);
        assertThat(approver.postJson(base() + "/rejection", Map.of("reason", "Late")).getStatus()).isEqualTo(409);
    }

    @Test
    void theDatabaseRefusesAReviewOfAPublishedVersionEvenIfTheServiceDidNot() throws Exception {
        ingest();
        Client approver = signedInAs(Role.POLICY_MANAGER);
        confirmAll(approver);
        approver.postEmpty(base() + "/publication");
        String value = criticalValueIds(view(approver)).getFirst();

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO core.policy_value_reviews (value_id, decision, reviewed_by)
                                VALUES (?, 'CONFIRMED', ?)
                                """)
                        .params(UUID.fromString(value), approver.userId)
                        .update())
                .hasMessageContaining("closed to review");
    }

    @Test
    void aRejectionNeedsAReasonAndClosesReview() throws Exception {
        ingest();

        assertThat(uploader.postJson(base() + "/rejection", Map.of("reason", " ")).getStatus()).isIn(400, 422);
        assertThat(uploader.postJson(base() + "/rejection", Map.of("reason", "Wrong wording uploaded")).getStatus())
                .isEqualTo(204);
        assertThat(view(uploader).get("rejectionReason").asString()).isEqualTo("Wrong wording uploaded");
        assertThat(review(uploader, criticalValueIds(view(uploader)).getFirst(), Map.of("decision", "CONFIRMED")).getStatus())
                .isEqualTo(409);
    }

    @Test
    void aRejectedVersionCanTakeANewWording() throws Exception {
        ingest();
        uploader.postJson(base() + "/rejection", Map.of("reason", "Wrong wording uploaded"));

        MockHttpServletResponse replaced = uploader.upload(
                "/api/v1/policies/" + policy + "/versions/" + version + "/wording", "right.pdf",
                ClaimFixtures.read("policies/family-floater-gold-v2.pdf"));

        assertThat(replaced.getStatus()).isEqualTo(201);
        assertThat(status()).isEqualTo("PROCESSING");
    }

    @Test
    void aReplacedWordingIsNotReadFurther() throws Exception {
        UUID first = uploadWording();
        ingestion.begin(first);
        uploader.upload(
                "/api/v1/policies/" + policy + "/versions/" + version + "/wording", "again.pdf",
                ClaimFixtures.read("policies/family-floater-gold-v2.pdf"));

        assertThat(ingestion.begin(first)).isFalse();
        assertThat(ingestion.pendingBatches(first)).isEmpty();
        ingestion.extractBatch(first, new PageRange(1, 2));
        verify(aiService, never()).extractPolicyPages(any());
    }

    @Test
    void aFailedBatchIsShownAndCanBeRetried() throws Exception {
        UUID document = uploadWording();
        ingestion.begin(document);
        when(aiService.extractPolicyPages(any())).thenThrow(new Failure("model_unavailable", true, List.of()));

        assertThatThrownBy(() -> ingestion.extractBatch(document, new PageRange(1, 2))).isInstanceOf(Failure.class);
        ingestion.recordBatchFailed(document, new PageRange(1, 2), "model_unavailable");

        JsonNode view = view(uploader);
        assertThat(view.get("batches").get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(uploader.postEmpty(base() + "/retry").getStatus()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT event_type FROM core.outbox_events WHERE aggregate_id = ? ORDER BY id")
                        .param(document)
                        .query(String.class)
                        .list())
                .containsExactly("POLICY_WORDING_ATTACHED", "POLICY_INGESTION_RETRY_REQUESTED");
    }

    @Test
    void readersSeeKnowledgeButCannotChangeIt() throws Exception {
        ingest();
        Client reviewer = signedInAs(Role.REVIEWER);
        String value = criticalValueIds(view(reviewer)).getFirst();

        assertThat(reviewer.getPath(base()).getStatus()).isEqualTo(200);
        assertThat(review(reviewer, value, Map.of("decision", "CONFIRMED")).getStatus()).isEqualTo(403);
        assertThat(reviewer.postEmpty(base() + "/publication").getStatus()).isEqualTo(403);
        assertThat(reviewer.postJson(base() + "/rejection", Map.of("reason", "x")).getStatus()).isEqualTo(403);
    }

    @Test
    void administratorsAndAnonymousCallersAreRefused() throws Exception {
        assertThat(signedInAs(Role.ADMIN).getPath(base()).getStatus()).isEqualTo(403);
        assertThat(anonymous().getPath(base()).getStatus()).isEqualTo(401);
    }

    @Test
    void aVersionOfAnotherPolicyIsNotFound() throws Exception {
        String other = fixtures.createPolicy();

        assertThat(uploader.getPath("/api/v1/policies/" + other + "/versions/" + version + "/knowledge").getStatus())
                .isEqualTo(404);
    }

    @Test
    void reviewsAndPublicationAreAudited() throws Exception {
        ingest();
        Client approver = signedInAs(Role.POLICY_MANAGER);
        confirmAll(approver);
        approver.postEmpty(base() + "/publication");

        assertThat(jdbc.sql("SELECT action FROM core.audit_events WHERE stream = ? ORDER BY sequence")
                        .param("policy:" + policy)
                        .query(String.class)
                        .list())
                .contains("POLICY_PAGES_EXTRACTED", "POLICY_KNOWLEDGE_READY_FOR_REVIEW", "POLICY_VALUE_CONFIRMED",
                        "POLICY_VERSION_PUBLISHED");
    }

    @Test
    void derivedKnowledgeCannotBeChangedByTheRuntimeRole() throws Exception {
        ingest();

        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            for (String table : List.of("policy_provisions", "policy_values", "policy_relations", "policy_findings",
                    "policy_page_extractions", "policy_value_reviews", "policy_batch_events")) {
                assertThatThrownBy(() -> statement.execute("DELETE FROM core." + table))
                        .as(table)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
            assertThatThrownBy(() -> statement.execute("UPDATE core.policy_values SET value = '1'"))
                    .hasMessageContaining("permission denied");
        }
    }
}
