package dev.prism.core.workflow.internal;

import static dev.prism.core.intelligence.internal.ExtractionFixtures.bill;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.claimForm;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.discharge;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.item;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.DocumentExtraction.Result;
import dev.prism.core.identity.Role;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import io.temporal.failure.ApplicationFailure;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Runs the workflow's activities, in order, against the real database and API. Only the AI is mocked. */
class ClaimProcessingFlowTest extends IntegrationTest {

    @Autowired
    private ClaimActivities activities;

    @Autowired
    private DocumentExtractionStep extraction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private ClaimFixtures fixtures;
    private Client processor;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        processor = fixtures.processor();
    }

    private String uploaded(String claimId, String type, String path) throws Exception {
        MockHttpServletResponse response = fixtures.upload(processor, claimId, type, "file.pdf", ClaimFixtures.read(path));
        return processor.bodyOf(response).get("id").asString();
    }

    /** A submitted claim with a form, a bill, and a summary, whose extraction results are scripted. */
    private String claimWithDocuments(Result... results) throws Exception {
        String claim = fixtures.draftClaimId();
        uploaded(claim, "CLAIM_FORM", "claims/appendectomy/claim-form.pdf");
        uploaded(claim, "HOSPITAL_BILL", "claims/appendectomy/hospital-bill.pdf");
        uploaded(claim, "DISCHARGE_SUMMARY", "claims/appendectomy/discharge-summary.pdf");
        processor.postEmpty("/api/v1/claims/" + claim + "/submission");
        when(aiService.extractDocument(any())).thenReturn(results[0], java.util.Arrays.copyOfRange(results, 1, results.length));
        return claim;
    }

    private void process(String claim) {
        activities.begin(claim);
        for (String document : activities.documentsToExtract(claim)) {
            extraction.extract(claim, document);
        }
    }

    private JsonNode facts(String claim) throws Exception {
        return processor.bodyOf(processor.getPath("/api/v1/claims/" + claim + "/facts"));
    }

    private JsonNode fact(JsonNode facts, String name) {
        return facts.get("facts").valueStream()
                .filter(node -> node.get("name").asString().equals(name) && node.get("documentId").isNull())
                .findFirst()
                .orElseThrow();
    }

    private String status(String claim) throws Exception {
        return processor.bodyOf(processor.getPath("/api/v1/claims/" + claim)).get("summary").get("status").asString();
    }

    private List<String> outbox(String claim) {
        return jdbc.sql("SELECT event_type FROM core.outbox_events WHERE aggregate_id = ? ORDER BY id")
                .param(UUID.fromString(claim))
                .query(String.class)
                .list();
    }

    private List<String> auditActions(String claim) {
        return jdbc.sql("SELECT action FROM core.audit_events WHERE stream = ? ORDER BY sequence")
                .param("claim:" + claim)
                .query(String.class)
                .list();
    }

    private static Result consistentBill() {
        return bill("106000.00", List.of(item("Room", "6000.00"), item("Surgery", "100000.00")));
    }

    @Test
    void submittingCommitsAnOutboxEventWithTheClaim() throws Exception {
        String claim = fixtures.submittedClaimId();

        assertThat(outbox(claim)).containsExactly("CLAIM_SUBMITTED");
    }

    @Test
    void aDraftAnnouncesNothing() throws Exception {
        String claim = fixtures.draftClaimId();
        fixtures.uploadBill(claim);
        processor.postJson("/api/v1/claims/" + claim + "/withdrawal", Map.of("reason", "ENTERED_IN_ERROR"));

        assertThat(outbox(claim)).isEmpty();
    }

    @Test
    void anAddedDocumentAndAWithdrawalAreAnnouncedOnceSubmitted() throws Exception {
        String claim = fixtures.submittedClaimId();
        uploaded(claim, "PRESCRIPTION", "claims/scanned/prescription.png");
        processor.postJson("/api/v1/claims/" + claim + "/withdrawal", Map.of("reason", "CLAIMANT_REQUEST"));

        assertThat(outbox(claim)).containsExactly("CLAIM_SUBMITTED", "CLAIM_DOCUMENTS_CHANGED", "CLAIM_WITHDRAWN");
    }

    @Test
    void aCompleteClaimReachesReviewWithSourcedFacts() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));

        process(claim);
        assertThat(activities.consolidate(claim)).isTrue();
        activities.readyForReview(claim);

        assertThat(status(claim)).isEqualTo("IN_REVIEW");
        JsonNode facts = facts(claim);
        assertThat(facts.get("sequence").asInt()).isEqualTo(1);
        assertThat(facts.get("complete").asBoolean()).isTrue();
        assertThat(facts.get("findings")).isEmpty();
        JsonNode admission = fact(facts, "admission_date");
        assertThat(admission.get("state").asString()).isEqualTo("PRESENT");
        assertThat(admission.get("value").asString()).isEqualTo("2026-03-14");
        assertThat(admission.get("sources")).hasSize(3);
        JsonNode source = admission.get("sources").get(0);
        assertThat(source.get("quote").asString()).isEqualTo("admission_date: 2026-03-14");
        assertThat(source.get("region")).hasSize(4);
        assertThat(facts.get("lineItems")).hasSize(2);
    }

    @Test
    void conflictingDocumentsAreShownAsConflictsInTheApi() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-15"));

        process(claim);
        activities.consolidate(claim);

        JsonNode facts = facts(claim);
        JsonNode admission = fact(facts, "admission_date");
        assertThat(admission.get("state").asString()).isEqualTo("CONFLICTING");
        assertThat(admission.get("value").isNull()).isTrue();
        assertThat(ClaimFixtures.values(facts.get("findings"), "code")).contains("VALUES_CONFLICT");
    }

    @Test
    void aMissingDocumentHoldsTheClaimUntilOneArrives() throws Exception {
        String claim = fixtures.draftClaimId();
        fixtures.uploadBill(claim);
        processor.postEmpty("/api/v1/claims/" + claim + "/submission");
        when(aiService.extractDocument(any())).thenReturn(consistentBill(), discharge("2026-03-14"), claimForm("2026-03-14", "106000.00"));

        process(claim);
        assertThat(activities.consolidate(claim)).isFalse();
        activities.awaitInformation(claim);
        assertThat(status(claim)).isEqualTo("AWAITING_INFORMATION");

        uploaded(claim, "DISCHARGE_SUMMARY", "claims/appendectomy/discharge-summary.pdf");
        uploaded(claim, "CLAIM_FORM", "claims/appendectomy/claim-form.pdf");
        process(claim);
        assertThat(status(claim)).isEqualTo("PROCESSING");
        assertThat(activities.consolidate(claim)).isTrue();

        assertThat(facts(claim).get("sequence").asInt()).isEqualTo(2);
        verify(aiService, times(3)).extractDocument(any());
    }

    @Test
    void extractionIsNotRepeatedForADocumentAlreadyDone() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));
        List<String> documents = new ArrayList<>(activities.documentsToExtract(claim));
        process(claim);
        clearInvocations(aiService);

        extraction.extract(claim, documents.getFirst());

        verify(aiService, never()).extractDocument(any());
        assertThat(activities.documentsToExtract(claim)).isEmpty();
    }

    @Test
    void aReplacedDocumentLeavesTheFactsAndItsReplacementIsExtracted() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-15"), discharge("2026-03-14"));
        process(claim);
        activities.consolidate(claim);
        assertThat(fact(facts(claim), "admission_date").get("state").asString()).isEqualTo("CONFLICTING");

        String summary = processor.bodyOf(processor.getPath("/api/v1/claims/" + claim)).get("documents").valueStream()
                .filter(document -> document.get("documentType").asString().equals("DISCHARGE_SUMMARY"))
                .findFirst().orElseThrow().get("id").asString();
        processor.upload(
                "/api/v1/claims/" + claim + "/documents/" + summary + "/replacement",
                "summary.pdf",
                ClaimFixtures.read("claims/conflicting/discharge-summary.pdf"));
        process(claim);
        activities.consolidate(claim);

        assertThat(fact(facts(claim), "admission_date").get("state").asString()).isEqualTo("PRESENT");
    }

    @Test
    void usageIsRecordedForSuccessAndForFailure() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = activities.documentsToExtract(claim).getFirst();
        when(aiService.extractDocument(any()))
                .thenThrow(new Failure("model_unavailable", true, List.of(run("UNAVAILABLE"))))
                .thenReturn(consistentBill());

        assertThatThrownBy(() -> extraction.extract(claim, document)).isInstanceOf(ApplicationFailure.class);
        extraction.extract(claim, document);

        assertThat(jdbc.sql("SELECT outcome FROM core.ai_runs WHERE claim_id = ? ORDER BY id")
                        .param(UUID.fromString(claim))
                        .query(String.class)
                        .list())
                .containsExactly("UNAVAILABLE", "SUCCEEDED");
    }

    @Test
    void aTransientFailureCanBeRetriedAndAPermanentOneCannot() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = activities.documentsToExtract(claim).getFirst();
        when(aiService.extractDocument(any()))
                .thenThrow(new Failure("model_unavailable", true, List.of()))
                .thenThrow(new Failure("document_unreadable", false, List.of()));

        assertThatThrownBy(() -> extraction.extract(claim, document))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.isNonRetryable()).isFalse();
                    assertThat(failure.getType()).isEqualTo("model_unavailable");
                });
        assertThatThrownBy(() -> extraction.extract(claim, document))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> assertThat(failure.isNonRetryable()).isTrue());
    }

    @Test
    void theProcessingViewReportsAFailureAndARetryRequestReachesTheOutbox() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = activities.documentsToExtract(claim).getFirst();
        activities.recordExtractionFailed(claim, document, "document_unreadable");

        String path = "/api/v1/claims/" + claim + "/processing";
        JsonNode view = processor.bodyOf(processor.getPath(path));
        assertThat(view.get("retryAvailable").asBoolean()).isTrue();
        JsonNode stage = view.get("stages").get(0);
        assertThat(stage.get("status").asString()).isEqualTo("FAILED");
        assertThat(stage.get("detailCode").asString()).isEqualTo("document_unreadable");

        assertThat(processor.postEmpty(path + "/retry").getStatus()).isEqualTo(202);
        assertThat(outbox(claim)).contains("CLAIM_RETRY_REQUESTED");
        assertThat(auditActions(claim)).contains("CLAIM_PROCESSING_RETRY_REQUESTED");
    }

    @Test
    void aRetryWithNothingFailedIsRefused() throws Exception {
        String claim = fixtures.submittedClaimId();

        assertThat(processor.postEmpty("/api/v1/claims/" + claim + "/processing/retry").getStatus()).isEqualTo(409);
    }

    @Test
    void aWithdrawnClaimIsLeftAloneByALateActivity() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = activities.documentsToExtract(claim).getFirst();
        processor.postJson("/api/v1/claims/" + claim + "/withdrawal", Map.of("reason", "CLAIMANT_REQUEST"));

        activities.begin(claim);
        activities.awaitInformation(claim);
        activities.readyForReview(claim);
        extraction.extract(claim, document);

        assertThat(status(claim)).isEqualTo("WITHDRAWN");
        verify(aiService, never()).extractDocument(any());
    }

    @Test
    void statusChangesAreIdempotentAndAuditedOnce() throws Exception {
        String claim = fixtures.submittedClaimId();

        activities.begin(claim);
        activities.begin(claim);

        assertThat(auditActions(claim)).containsOnlyOnce("CLAIM_PROCESSING_STARTED");
    }

    @Test
    void theAuditTrailRecordsSendingAndResultButNoContent() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));
        process(claim);
        activities.consolidate(claim);

        assertThat(auditActions(claim)).contains("DOCUMENT_SENT_FOR_EXTRACTION", "DOCUMENT_EXTRACTED", "CLAIM_FACTS_CONSOLIDATED");
        assertThat(jdbc.sql("SELECT coalesce(string_agg(details::text, ' '), '') FROM core.audit_events WHERE stream = ?")
                        .param("claim:" + claim)
                        .query(String.class)
                        .single())
                .doesNotContain("Synthetic", "appendicitis", "106000");
    }

    @Test
    void theTimelineHidesRoutineSendingButShowsResults() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));
        process(claim);

        JsonNode timeline = processor.bodyOf(processor.getPath("/api/v1/claims/" + claim + "/timeline"));
        assertThat(ClaimFixtures.values(timeline, "action"))
                .contains("DOCUMENT_EXTRACTED")
                .doesNotContain("DOCUMENT_SENT_FOR_EXTRACTION");
    }

    @Test
    void readersCanSeeFactsAndProcessingButNotRetry() throws Exception {
        String claim = fixtures.submittedClaimId();
        Client reviewer = signedInAs(Role.REVIEWER);

        assertThat(reviewer.getPath("/api/v1/claims/" + claim + "/facts").getStatus()).isEqualTo(200);
        assertThat(reviewer.getPath("/api/v1/claims/" + claim + "/processing").getStatus()).isEqualTo(200);
        assertThat(reviewer.postEmpty("/api/v1/claims/" + claim + "/processing/retry").getStatus()).isEqualTo(403);
    }

    @Test
    void anAdministratorHasNoAccessToClaimIntelligence() throws Exception {
        String claim = fixtures.submittedClaimId();
        Client admin = signedInAs(Role.ADMIN);

        assertThat(admin.getPath("/api/v1/claims/" + claim + "/facts").getStatus()).isEqualTo(403);
        assertThat(admin.getPath("/api/v1/claims/" + claim + "/processing").getStatus()).isEqualTo(403);
    }

    @Test
    void anonymousCallersAreRefused() throws Exception {
        String claim = fixtures.submittedClaimId();

        assertThat(anonymous().getPath("/api/v1/claims/" + claim + "/facts").getStatus()).isEqualTo(401);
        assertThat(anonymous().getPath("/api/v1/claims/" + claim + "/processing").getStatus()).isEqualTo(401);
    }

    @Test
    void draftsStayHiddenFromReaders() throws Exception {
        String draft = fixtures.draftClaimId();
        Client reviewer = signedInAs(Role.REVIEWER);

        assertThat(reviewer.getPath("/api/v1/claims/" + draft + "/facts").getStatus()).isEqualTo(404);
        assertThat(reviewer.getPath("/api/v1/claims/" + draft + "/processing").getStatus()).isEqualTo(404);
    }

    @Test
    void anUnknownClaimIsNotFound() throws Exception {
        assertThat(processor.getPath("/api/v1/claims/" + UUID.randomUUID() + "/facts").getStatus()).isEqualTo(404);
    }

    @Test
    void aClaimNotYetProcessedHasNoFactsButListsItsDocuments() throws Exception {
        String claim = fixtures.submittedClaimId();

        JsonNode facts = facts(claim);
        assertThat(facts.get("sequence").isNull()).isTrue();
        assertThat(facts.get("facts")).isEmpty();
        assertThat(facts.get("documents")).hasSize(1);
    }

    @Test
    void aDocumentFromAnotherClaimCannotBeReadThroughThisOne() throws Exception {
        String first = fixtures.submittedClaimId();
        String second = fixtures.submittedClaimId();
        String foreign = activities.documentsToExtract(second).getFirst();

        assertThatThrownBy(() -> extraction.extract(first, foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivedRecordsCannotBeChangedOrDeletedByTheRuntimeRole() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));
        process(claim);
        activities.consolidate(claim);

        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            for (String table : List.of(
                    "ai_runs", "document_extractions", "claim_consolidations", "claim_facts",
                    "claim_fact_sources", "claim_line_items", "validation_findings", "stage_events")) {
                assertThatThrownBy(() -> statement.execute("DELETE FROM core." + table))
                        .as(table)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
            assertThatThrownBy(() -> statement.execute("UPDATE core.claim_facts SET value = 'changed'"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void theDatabaseRefusesAPresentSourceWithoutACitation() throws Exception {
        String claim = claimWithDocuments(claimForm("2026-03-14", "106000.00"), consistentBill(), discharge("2026-03-14"));
        process(claim);
        activities.consolidate(claim);
        UUID fact = jdbc.sql("""
                        SELECT f.id FROM core.claim_facts f JOIN core.claim_consolidations c ON c.id = f.consolidation_id
                        WHERE c.claim_id = ? LIMIT 1
                        """)
                .param(UUID.fromString(claim))
                .query(UUID.class)
                .single();
        UUID document = jdbc.sql("SELECT id FROM core.documents WHERE owner_id = ? LIMIT 1")
                .param(UUID.fromString(claim))
                .query(UUID.class)
                .single();

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO core.claim_fact_sources (fact_id, document_id, state, confidence, grounding)
                                VALUES (?, ?, 'PRESENT', 'HIGH', 'NONE')
                                """)
                        .params(fact, document)
                        .update())
                .hasMessageContaining("claim_fact_sources");
    }

    private record RecordedDispatch(Outbox.Event event, UUID claim) {}

    /** Records deliveries, and can fail for one claim, as Temporal would during an outage. */
    private static final class FakeDispatcher implements WorkflowDispatcher {
        private final List<RecordedDispatch> delivered = new ArrayList<>();
        private UUID failing;

        @Override
        public void dispatch(Outbox.Event event, UUID claim) {
            if (claim.equals(failing)) {
                throw new IllegalStateException("workflow engine unavailable");
            }
            delivered.add(new RecordedDispatch(event, claim));
        }

        List<RecordedDispatch> forClaim(UUID claim) {
            return delivered.stream().filter(dispatch -> dispatch.claim().equals(claim)).toList();
        }
    }

    /**
     * Runs the relay until a pass delivers nothing. The shared test database holds other tests'
     * pending events too, and the relay takes them oldest first, a batch at a time.
     */
    private void relay(FakeDispatcher dispatcher) {
        OutboxRelay relay = new OutboxRelay(jdbc, dispatcher);
        Integer published;
        do {
            published = transactions.execute(status -> relay.publishPending());
        } while (published != null && published > 0);
    }

    @Test
    void theRelayDeliversOnceAndInOrder() throws Exception {
        String claim = fixtures.submittedClaimId();
        uploaded(claim, "PRESCRIPTION", "claims/scanned/prescription.png");
        FakeDispatcher dispatcher = new FakeDispatcher();
        UUID id = UUID.fromString(claim);

        relay(dispatcher);
        relay(dispatcher);

        assertThat(dispatcher.forClaim(id)).extracting(RecordedDispatch::event)
                .containsExactly(Outbox.Event.CLAIM_SUBMITTED, Outbox.Event.CLAIM_DOCUMENTS_CHANGED);
    }

    @Test
    void anOutageLosesNothingAndKeepsOrderWhenDeliveryResumes() throws Exception {
        String claim = fixtures.submittedClaimId();
        uploaded(claim, "PRESCRIPTION", "claims/scanned/prescription.png");
        UUID id = UUID.fromString(claim);
        FakeDispatcher dispatcher = new FakeDispatcher();
        dispatcher.failing = id;

        relay(dispatcher);

        assertThat(dispatcher.forClaim(id)).isEmpty();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM core.outbox_events
                        WHERE aggregate_id = ? AND published_at IS NULL
                        """)
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        // Each relay pass tries the claim's first event again, and counts the attempt.
        assertThat(jdbc.sql("SELECT sum(attempts) FROM core.outbox_events WHERE aggregate_id = ?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isPositive();

        dispatcher.failing = null;
        relay(dispatcher);

        assertThat(dispatcher.forClaim(id)).extracting(RecordedDispatch::event)
                .containsExactly(Outbox.Event.CLAIM_SUBMITTED, Outbox.Event.CLAIM_DOCUMENTS_CHANGED);
    }

    @Test
    void oneClaimsOutageDoesNotHoldUpAnother() throws Exception {
        UUID blocked = UUID.fromString(fixtures.submittedClaimId());
        UUID healthy = UUID.fromString(fixtures.submittedClaimId());
        FakeDispatcher dispatcher = new FakeDispatcher();
        dispatcher.failing = blocked;

        relay(dispatcher);

        assertThat(dispatcher.forClaim(healthy)).hasSize(1);
    }

    @Test
    void theExtractionRequestCarriesTheDocumentBytesAndDeclaredType() throws Exception {
        String claim = fixtures.submittedClaimId();
        String document = activities.documentsToExtract(claim).getFirst();
        when(aiService.extractDocument(any())).thenAnswer(call -> {
            DocumentExtraction.Request request = call.getArgument(0);
            assertThat(request.documentId()).hasToString(document);
            assertThat(request.declaredType()).isEqualTo("HOSPITAL_BILL");
            assertThat(request.mediaType()).isEqualTo("application/pdf");
            assertThat(request.content()).isEqualTo(ClaimFixtures.read(ClaimFixtures.BILL));
            return consistentBill();
        });

        extraction.extract(claim, document);

        verify(aiService).extractDocument(any());
    }
}
