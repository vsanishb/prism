package dev.prism.core.intelligence;

import static dev.prism.core.intelligence.internal.ExtractionFixtures.bill;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.claimForm;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.discharge;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.item;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.goldWording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import dev.prism.core.aiclient.PolicyAnalysis;
import dev.prism.core.aiclient.PolicySearch;
import dev.prism.core.identity.Role;
import dev.prism.core.policyknowledge.PolicyIngestion;
import dev.prism.core.policyknowledge.PolicyIngestion.PageRange;
import dev.prism.core.retrieval.PolicyIndexing;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Claim facts against a published, indexed policy, on the real database. Only the AI service is mocked. */
class PolicyAnalysisFlowTest extends IntegrationTest {

    private static final ModelRun AGENT_RUN = new ModelRun(
            "agent_analysis", "deepseek/deepseek-v4.1-flash-1017", "DeepSeek", "agent_analysis.v1", 1, "SUCCEEDED", 900, 200,
            new BigDecimal("0.0003"), 2100);

    @Autowired
    private ClaimIntelligence intelligence;

    @Autowired
    private PolicyIngestion ingestion;

    @Autowired
    private PolicyIndexing indexing;

    @Autowired
    private JdbcClient jdbc;

    private ClaimFixtures fixtures;
    private Client manager;
    private Client processor;
    private String policy;
    private String version;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        manager = fixtures.policyManager();
        processor = fixtures.processor();
        policy = fixtures.createPolicy();
        // Consecutive versions: continuous cover runs from the earlier one's start.
        fixtures.addVersion(policy, "2024-04-01", "2025-03-31");
        version = fixtures.addVersion(policy, "2025-04-01", "2026-03-31");
    }

    private String versionPath() {
        return "/api/v1/policies/" + policy + "/versions/" + version;
    }

    private void publish() throws Exception {
        MockHttpServletResponse upload = manager.upload(versionPath() + "/wording", "wording.pdf",
                ClaimFixtures.read("policies/family-floater-gold-v1.pdf"));
        UUID document = UUID.fromString(manager.bodyOf(upload).get("id").asString());
        when(aiService.extractPolicyPages(any())).thenReturn(goldWording());
        ingestion.begin(document);
        for (PageRange range : ingestion.pendingBatches(document)) {
            ingestion.extractBatch(document, range);
        }
        ingestion.assemble(document);
        for (JsonNode provision : knowledge().get("provisions")) {
            for (JsonNode value : provision.get("values")) {
                if (value.get("critical").asBoolean()) {
                    manager.postJson(versionPath() + "/knowledge/values/" + value.get("id").asString() + "/review",
                            Map.of("decision", "CONFIRMED"));
                }
            }
        }
        assertThat(signedInAs(Role.POLICY_MANAGER).postEmpty(versionPath() + "/knowledge/publication").getStatus()).isEqualTo(204);
    }

    private void index() {
        when(aiService.indexPolicyVersion(any())).thenReturn(
                new PolicySearch.IndexResult(UUID.fromString(version), 6, "openai/text-embedding-3-small", "retrieval-1", List.of()));
        indexing.begin(UUID.fromString(version));
        indexing.build(UUID.fromString(version));
    }

    private JsonNode knowledge() throws Exception {
        return manager.bodyOf(manager.getPath(versionPath() + "/knowledge"));
    }

    private Map<String, UUID> provisions() throws Exception {
        Map<String, UUID> ids = new HashMap<>();
        for (JsonNode provision : knowledge().get("provisions")) {
            if (!provision.get("key").isNull() && !provision.get("key").asString().isEmpty()) {
                ids.put(provision.get("key").asString(), UUID.fromString(provision.get("id").asString()));
            }
        }
        return ids;
    }

    /** A submitted claim on this policy, extracted and consolidated as the workflow would. */
    private UUID consolidatedClaim() throws Exception {
        MockHttpServletResponse created = fixtures.postClaim(processor, fixtures.claimBody(policy), UUID.randomUUID().toString());
        String claim = processor.bodyOf(created).get("summary").get("id").asString();
        for (String[] document : new String[][] {
                {"CLAIM_FORM", "claims/appendectomy/claim-form.pdf"},
                {"HOSPITAL_BILL", "claims/appendectomy/hospital-bill.pdf"},
                {"DISCHARGE_SUMMARY", "claims/appendectomy/discharge-summary.pdf"}}) {
            fixtures.upload(processor, claim, document[0], "file.pdf", ClaimFixtures.read(document[1]));
        }
        processor.postEmpty("/api/v1/claims/" + claim + "/submission");
        when(aiService.extractDocument(any())).thenReturn(
                claimForm("2026-03-14", "106000.00"),
                bill("106000.00", List.of(item("Room", "6000.00"), item("Surgery", "100000.00"))),
                discharge("2026-03-14"));
        UUID id = UUID.fromString(claim);
        for (UUID document : intelligence.documentsToExtract(id)) {
            intelligence.extract(id, document);
        }
        intelligence.consolidate(id);
        return id;
    }

    /** A finding as the AI service would return it, citing the given provisions and applying the given values. */
    private static PolicyAnalysis.Finding finding(
            int position, String kind, String status, List<PolicyAnalysis.Citation> citations,
            List<PolicyAnalysis.AppliedValue> values, List<UUID> facts) {
        return new PolicyAnalysis.Finding(
                position, kind, "Question " + position, status, null, facts, citations, "Scripted reasoning.", List.of(), values,
                List.of(), 0, 1, List.of("query"));
    }

    private static PolicyAnalysis.Citation cite(UUID provision, String quote) {
        return new PolicyAnalysis.Citation(provision, null, "", "BENEFIT", quote);
    }

    private PolicyAnalysis.Result answer(UUID claim, List<PolicyAnalysis.Finding> findings) {
        return new PolicyAnalysis.Result(
                claim, UUID.fromString(version), "agent-1", "agent_plan.v1", "agent_analysis.v1", findings, 0, List.of(AGENT_RUN));
    }

    private PolicyAnalysis.Request sentRequest() {
        ArgumentCaptor<PolicyAnalysis.Request> sent = ArgumentCaptor.forClass(PolicyAnalysis.Request.class);
        verify(aiService).analyzeClaim(sent.capture());
        return sent.getValue();
    }

    private UUID valueOf(PolicyAnalysis.Request request, UUID provision) {
        return request.policyValues().stream().filter(value -> value.provisionId().equals(provision)).findFirst().orElseThrow().id();
    }

    private JsonNode assessment(Client client, UUID claim) throws Exception {
        return client.bodyOf(client.getPath("/api/v1/claims/" + claim + "/assessment")).get("assessment");
    }

    /** Runs the analysis with an answer built from the request it receives, as the agent would. */
    private void analyzeWith(UUID claim, java.util.function.Function<PolicyAnalysis.Request, List<PolicyAnalysis.Finding>> findings) {
        when(aiService.analyzeClaim(any())).thenAnswer(call -> answer(claim, findings.apply(call.getArgument(0))));
        intelligence.analyze(claim);
    }

    @Test
    void onlyDeIdentifiedFactsAndConfirmedValuesAreSent() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        analyzeWith(claim, request -> List.of());

        PolicyAnalysis.Request request = sentRequest();
        assertThat(request.facts()).extracting(PolicyAnalysis.Fact::name)
                .contains("admission_date", "diagnosis", "claimed_amount")
                .doesNotContain("patient_name", "hospital_name", "claimant_name", "policy_number");
        assertThat(json.writeValueAsString(request)).doesNotContain("Synthetic Patient", "Synthetic Hospital", "Synthetic Claimant");
        assertThat(request.coverStart()).hasToString("2024-04-01");
        assertThat(request.sumInsured()).isEqualByComparingTo("500000.00");
        assertThat(request.policyValues()).extracting(PolicyAnalysis.PolicyValue::kind).doesNotContain("TEXT").isNotEmpty();
    }

    @Test
    void findingsAreStoredWithTheirFactsCitationsAndCalculations() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        Map<String, UUID> provisions = provisions();
        analyzeWith(claim, request -> {
            UUID diagnosis = request.facts().stream().filter(fact -> fact.name().equals("diagnosis")).findFirst().orElseThrow().id();
            return List.of(
                    finding(1, "WAITING_PERIOD", "POTENTIALLY_EXCLUDED",
                            List.of(cite(provisions.get("3.2"), "A 24-month waiting period applies to cataract")),
                            List.of(new PolicyAnalysis.AppliedValue(valueOf(request, provisions.get("3.2")), "WAITING_PERIOD")),
                            List.of(diagnosis)),
                    finding(2, "LIMIT", "LIMITED",
                            List.of(cite(provisions.get("2.6"), "Cataract surgery INR 40,000 per eye")),
                            List.of(new PolicyAnalysis.AppliedValue(valueOf(request, provisions.get("2.6")), "AMOUNT_LIMIT")),
                            List.of()));
        });

        JsonNode view = assessment(signedInAs(Role.REVIEWER), claim);
        assertThat(view.get("current").asBoolean()).isTrue();
        assertThat(view.get("coverStart").asString()).isEqualTo("2024-04-01");
        assertThat(view.get("wordingDocumentId").isNull()).isFalse();
        JsonNode waiting = view.get("findings").get(0);
        assertThat(waiting.get("status").asString()).isEqualTo("POTENTIALLY_EXCLUDED");
        assertThat(waiting.get("facts").get(0).get("name").asString()).isEqualTo("diagnosis");
        assertThat(waiting.get("citations").get(0).get("key").asString()).isEqualTo("3.2");
        assertThat(waiting.get("citations").get(0).get("pageStart").asInt()).isEqualTo(2);
        assertThat(waiting.get("citations").get(0).get("region")).hasSize(4);
        JsonNode dated = waiting.get("calculations").get(0);
        assertThat(dated.get("resultDate").asString()).isEqualTo("2026-04-01");
        assertThat(dated.get("outcome").asString()).contains("falls within the waiting period");
        assertThat(dated.get("inputs").get(0).get(0).asString()).isEqualTo("Policy value");
        JsonNode limit = view.get("findings").get(1).get("calculations").get(0);
        assertThat(limit.get("resultAmount").decimalValue()).isEqualByComparingTo("40000.00");
        assertThat(limit.get("outcome").asString()).contains("above this limit by INR 66000.00");

        assertThat(jdbc.sql("SELECT count(*) FROM core.ai_runs WHERE claim_id = ? AND task = 'agent_analysis'")
                        .param(claim).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT action FROM core.audit_events WHERE stream = ? ORDER BY sequence")
                        .param("claim:" + claim).query(String.class).list())
                .contains("CLAIM_SENT_FOR_POLICY_ANALYSIS", "CLAIM_POLICY_ANALYZED");
    }

    @Test
    void aCitationOutsideThisVersionInvalidatesItsFinding() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        Map<String, UUID> provisions = provisions();
        analyzeWith(claim, request -> List.of(finding(1, "LIMIT", "LIMITED",
                List.of(cite(UUID.randomUUID(), "Cataract surgery INR 40,000 per eye")),
                List.of(new PolicyAnalysis.AppliedValue(valueOf(request, provisions.get("2.6")), "AMOUNT_LIMIT")),
                List.of())));

        JsonNode finding = assessment(processor, claim).get("findings").get(0);
        assertThat(finding.get("status").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(finding.get("proposedStatus").asString()).isEqualTo("LIMITED");
        assertThat(finding.get("citations")).isEmpty();
        assertThat(finding.get("calculations")).isEmpty();
        assertThat(finding.get("problems").toString()).contains("NOT_IN_THIS_CLAIM_OR_VERSION");
    }

    @Test
    void aMisquotedCitationIsNotKept() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        Map<String, UUID> provisions = provisions();
        analyzeWith(claim, request -> List.of(finding(1, "LIMIT", "LIMITED",
                List.of(cite(provisions.get("2.6"), "Cataract surgery INR 4,00,000 per eye")), List.of(), List.of())));

        JsonNode finding = assessment(processor, claim).get("findings").get(0);
        assertThat(finding.get("status").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(finding.get("citations")).isEmpty();
    }

    @Test
    void aValueFromAProvisionNotCitedIsNotComputed() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        Map<String, UUID> provisions = provisions();
        analyzeWith(claim, request -> List.of(finding(1, "LIMIT", "LIMITED",
                List.of(cite(provisions.get("2.6"), "Cataract surgery INR 40,000 per eye")),
                List.of(new PolicyAnalysis.AppliedValue(valueOf(request, provisions.get("3.2")), "WAITING_PERIOD")),
                List.of())));

        JsonNode finding = assessment(processor, claim).get("findings").get(0);
        assertThat(finding.get("status").asString()).isEqualTo("LIMITED");
        assertThat(finding.get("calculations")).isEmpty();
        assertThat(finding.get("problems").toString()).contains("VALUE_REJECTED");
    }

    @Test
    void analysisIsIdempotentForTheSameFacts() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        analyzeWith(claim, request -> List.of());
        intelligence.analyze(claim);

        verify(aiService, times(1)).analyzeClaim(any());
        assertThat(jdbc.sql("SELECT count(*) FROM core.policy_assessments WHERE claim_id = ?").param(claim).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void anUnpublishedOrUnindexedPolicyStopsWithAClearCodeAndNothingIsSent() throws Exception {
        UUID claim = consolidatedClaim();
        assertThatThrownBy(() -> intelligence.analyze(claim))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.code()).isEqualTo("policy_not_published"));

        publish();
        assertThatThrownBy(() -> intelligence.analyze(claim))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.code()).isEqualTo("policy_not_searchable"));
        verify(aiService, never()).analyzeClaim(any());
    }

    @Test
    void aFailedAnalysisKeepsItsUsage() throws Exception {
        publish();
        index();
        UUID claim = consolidatedClaim();
        when(aiService.analyzeClaim(any())).thenThrow(new Failure("model_unavailable", true, List.of(AGENT_RUN)));

        assertThatThrownBy(() -> intelligence.analyze(claim)).isInstanceOf(Failure.class);
        assertThat(jdbc.sql("SELECT count(*) FROM core.ai_runs WHERE claim_id = ? AND task = 'agent_analysis'")
                        .param(claim).query(Integer.class).single()).isEqualTo(1);
        assertThat(assessment(processor, claim).isNull()).isTrue();
    }

    @Test
    void administratorsAndAnonymousCallersAreRefused() throws Exception {
        UUID claim = consolidatedClaim();
        assertThat(signedInAs(Role.ADMIN).getPath("/api/v1/claims/" + claim + "/assessment").getStatus()).isEqualTo(403);
        assertThat(anonymous().getPath("/api/v1/claims/" + claim + "/assessment").getStatus()).isEqualTo(401);
    }

    @Test
    void assessmentsCannotBeChangedByTheRuntimeRole() throws Exception {
        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            for (String table : List.of("policy_assessments", "assessment_findings", "assessment_citations", "assessment_facts",
                    "assessment_calculations")) {
                assertThatThrownBy(() -> statement.execute("DELETE FROM core." + table))
                        .as(table)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
    }
}
