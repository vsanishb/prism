package dev.prism.core.retrieval;

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
import dev.prism.core.aiclient.PolicySearch;
import dev.prism.core.identity.Role;
import dev.prism.core.policyknowledge.PolicyIngestion;
import dev.prism.core.policyknowledge.PolicyIngestion.PageRange;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Publication, indexing, and search on the real database. Only the AI service is mocked. */
class PolicySearchFlowTest extends IntegrationTest {

    private static final String WORDING = "policies/family-floater-gold-v1.pdf";
    private static final ModelRun EMBEDDING_RUN = new ModelRun(
            "embedding", "openai/text-embedding-3-small", "Azure", "none", 1, "SUCCEEDED", 300, null, new BigDecimal("0.000006"), 400);

    @Autowired
    private PolicyIngestion ingestion;

    @Autowired
    private PolicyIndexing indexing;

    @Autowired
    private JdbcClient jdbc;

    private ClaimFixtures fixtures;
    private Client uploader;
    private Client publisher;
    private String policy;
    private String version;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        uploader = fixtures.policyManager();
        publisher = signedInAs(Role.POLICY_MANAGER);
        policy = fixtures.createPolicy();
        version = fixtures.addVersion(policy, "2025-04-01", "2026-03-31");
    }

    private String versionPath() {
        return "/api/v1/policies/" + policy + "/versions/" + version;
    }

    private UUID versionId() {
        return UUID.fromString(version);
    }

    private JsonNode knowledge() throws Exception {
        return uploader.bodyOf(uploader.getPath(versionPath() + "/knowledge"));
    }

    private void ingestAndReview() throws Exception {
        MockHttpServletResponse upload = uploader.upload(versionPath() + "/wording", "wording.pdf", ClaimFixtures.read(WORDING));
        UUID document = UUID.fromString(uploader.bodyOf(upload).get("id").asString());
        when(aiService.extractPolicyPages(any())).thenReturn(goldWording());
        ingestion.begin(document);
        for (PageRange range : ingestion.pendingBatches(document)) {
            ingestion.extractBatch(document, range);
        }
        ingestion.assemble(document);
        for (JsonNode provision : knowledge().get("provisions")) {
            for (JsonNode value : provision.get("values")) {
                if (value.get("critical").asBoolean()) {
                    uploader.postJson(
                            versionPath() + "/knowledge/values/" + value.get("id").asString() + "/review",
                            Map.of("decision", "CONFIRMED"));
                }
            }
        }
    }

    private void publish() throws Exception {
        ingestAndReview();
        assertThat(publisher.postEmpty(versionPath() + "/knowledge/publication").getStatus()).isEqualTo(204);
    }

    private void publishAndIndex() throws Exception {
        publish();
        when(aiService.indexPolicyVersion(any()))
                .thenReturn(new PolicySearch.IndexResult(versionId(), 14, "openai/text-embedding-3-small", "retrieval-1", List.of(EMBEDDING_RUN)));
        assertThat(indexing.begin(versionId())).isTrue();
        indexing.build(versionId());
    }

    private Map<String, UUID> provisionIds() throws Exception {
        return StreamSupport.stream(knowledge().get("provisions").spliterator(), false)
                .filter(provision -> !provision.get("key").isNull())
                .collect(java.util.stream.Collectors.toMap(
                        provision -> provision.get("key").asString(),
                        provision -> UUID.fromString(provision.get("id").asString()),
                        (first, second) -> first));
    }

    private PolicySearch.SearchResult answer(UUID resultId, UUID relatedId, List<String> degraded) {
        var score = new PolicySearch.StageScore(1, 0.8);
        var result = new PolicySearch.Result(
                resultId, UUID.randomUUID(), "2.6", "BENEFIT", "Cataract surgery", List.of(), 1, 1, "Cataract surgery text",
                1, 1, 1, false, null, score, score, score, degraded.isEmpty() ? score : null,
                List.of(new PolicySearch.RelatedLink(relatedId, "LIMITS", "OUTGOING")));
        var hit = new PolicySearch.StageHit(resultId, "2.6", "BENEFIT", "Cataract surgery", 1, 0.8);
        var related = new PolicySearch.RelatedProvision(relatedId, "3.2", "WAITING_PERIOD", "Waiting period", 2, 2, "24 months");
        return new PolicySearch.SearchResult(
                versionId(), degraded, "openai/text-embedding-3-small", degraded.isEmpty() ? "qwen/qwen3-reranker-8b" : null,
                List.of(result), List.of(hit), List.of(hit), List.of(related), List.of(EMBEDDING_RUN));
    }

    private MockHttpServletResponse search(Client client, Object body) throws Exception {
        return client.postJson(versionPath() + "/search", body);
    }

    @Test
    void publishingAnnouncesTheVersionForIndexingInTheSameTransaction() throws Exception {
        publish();

        assertThat(jdbc.sql("SELECT count(*) FROM core.outbox_events WHERE event_type = 'POLICY_VERSION_PUBLISHED' AND aggregate_id = ?")
                        .param(versionId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(uploader.bodyOf(uploader.getPath(versionPath() + "/search-index")).get("status").asString())
                .isEqualTo("QUEUED");
    }

    @Test
    void anUnpublishedVersionIsNotIndexedOrSearched() throws Exception {
        ingestAndReview();

        assertThat(indexing.begin(versionId())).isFalse();
        assertThatThrownBy(() -> indexing.build(versionId())).isInstanceOf(Failure.class);
        assertThat(uploader.bodyOf(uploader.getPath(versionPath() + "/search-index")).get("status").asString())
                .isEqualTo("NOT_PUBLISHED");
        MockHttpServletResponse refused = search(uploader, Map.of("query", "cataract"));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(uploader.bodyOf(refused).get("code").asString()).isEqualTo("not_published");
        verify(aiService, never()).searchPolicyVersion(any());
    }

    @Test
    void indexingSendsThePublishedProvisionsAndRelationsAndRecordsTheResult() throws Exception {
        publishAndIndex();

        ArgumentCaptor<PolicySearch.IndexRequest> sent = ArgumentCaptor.forClass(PolicySearch.IndexRequest.class);
        verify(aiService).indexPolicyVersion(sent.capture());
        Map<String, UUID> ids = provisionIds();
        assertThat(sent.getValue().versionId()).isEqualTo(versionId());
        assertThat(sent.getValue().provisions()).extracting(PolicySearch.Provision::id).containsAll(ids.values());
        assertThat(sent.getValue().relations()).isNotEmpty().allSatisfy(relation -> {
            assertThat(ids.values()).contains(relation.fromProvisionId(), relation.toProvisionId());
        });

        JsonNode index = uploader.bodyOf(uploader.getPath(versionPath() + "/search-index"));
        assertThat(index.get("status").asString()).isEqualTo("READY");
        assertThat(index.get("chunkCount").asInt()).isEqualTo(14);
        assertThat(index.get("embeddingModel").asString()).isEqualTo("openai/text-embedding-3-small");
        assertThat(jdbc.sql("SELECT count(*) FROM core.ai_runs WHERE version_id = ?").param(versionId()).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM core.audit_events WHERE action = 'POLICY_SEARCH_INDEX_BUILT' AND target_id = ?")
                        .param(version)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void indexingIsIdempotent() throws Exception {
        publishAndIndex();

        assertThat(indexing.begin(versionId())).isTrue();
        indexing.build(versionId());

        verify(aiService, times(1)).indexPolicyVersion(any());
        assertThat(uploader.bodyOf(uploader.getPath(versionPath() + "/search-index")).get("status").asString())
                .isEqualTo("READY");
    }

    @Test
    void aFailedIndexIsShownWithItsUsageAndCanBeRetriedByAManager() throws Exception {
        publish();
        ModelRun failed = new ModelRun("embedding", "m", null, "none", 1, "UNAVAILABLE", null, null, null, 30);
        when(aiService.indexPolicyVersion(any())).thenThrow(new Failure("model_unavailable", true, List.of(failed)));
        indexing.begin(versionId());

        assertThatThrownBy(() -> indexing.build(versionId())).isInstanceOf(Failure.class);
        indexing.recordFailed(versionId(), "model_unavailable");

        JsonNode index = uploader.bodyOf(uploader.getPath(versionPath() + "/search-index"));
        assertThat(index.get("status").asString()).isEqualTo("FAILED");
        assertThat(index.get("failureCode").asString()).isEqualTo("model_unavailable");
        assertThat(jdbc.sql("SELECT outcome FROM core.ai_runs WHERE version_id = ?").param(versionId()).query(String.class).list())
                .containsExactly("UNAVAILABLE");

        Client reviewer = signedInAs(Role.REVIEWER);
        assertThat(reviewer.postEmpty(versionPath() + "/search-index/retry").getStatus()).isEqualTo(403);
        assertThat(uploader.postEmpty(versionPath() + "/search-index/retry").getStatus()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT count(*) FROM core.outbox_events WHERE event_type = 'POLICY_INDEX_RETRY_REQUESTED' AND aggregate_id = ?")
                        .param(versionId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aReadyIndexCannotBeRetriedAndALateFailureDoesNotHideIt() throws Exception {
        publishAndIndex();

        assertThat(uploader.postEmpty(versionPath() + "/search-index/retry").getStatus()).isEqualTo(409);
        indexing.recordFailed(versionId(), "timed_out");
        assertThat(uploader.bodyOf(uploader.getPath(versionPath() + "/search-index")).get("status").asString())
                .isEqualTo("READY");
    }

    @Test
    void searchingBeforeTheIndexIsReadyIsRefused() throws Exception {
        publish();

        MockHttpServletResponse refused = search(uploader, Map.of("query", "cataract"));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(uploader.bodyOf(refused).get("code").asString()).isEqualTo("index_not_ready");
    }

    @Test
    void aSearchReturnsEveryStageWithSourceLocationsAndRecordsUsage() throws Exception {
        publishAndIndex();
        Map<String, UUID> ids = provisionIds();
        when(aiService.searchPolicyVersion(any())).thenReturn(answer(ids.get("2.6"), ids.get("3.2"), List.of()));
        Client reader = signedInAs(Role.REVIEWER);

        MockHttpServletResponse response = search(reader, Map.of("query", "  cataract waiting period  ", "types", List.of("BENEFIT")));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = reader.bodyOf(response);
        assertThat(body.get("query").asString()).isEqualTo("cataract waiting period");
        assertThat(body.get("documentId").isNull()).isFalse();
        JsonNode top = body.get("results").get(0);
        assertThat(top.get("provisionId").asString()).isEqualTo(ids.get("2.6").toString());
        assertThat(top.get("quote").isNull()).isFalse();
        assertThat(top.get("region")).hasSize(4);
        assertThat(top.get("rerank").get("rank").asInt()).isEqualTo(1);
        assertThat(body.get("related").get(0).get("provisionId").asString()).isEqualTo(ids.get("3.2").toString());
        assertThat(body.get("semantic")).hasSize(1);
        assertThat(body.get("lexical")).hasSize(1);

        ArgumentCaptor<PolicySearch.SearchRequest> sent = ArgumentCaptor.forClass(PolicySearch.SearchRequest.class);
        verify(aiService).searchPolicyVersion(sent.capture());
        assertThat(sent.getValue().types()).containsExactly("BENEFIT");
        assertThat(sent.getValue().limit()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM core.ai_runs WHERE version_id = ?").param(versionId()).query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void aDegradedSearchSaysSo() throws Exception {
        publishAndIndex();
        Map<String, UUID> ids = provisionIds();
        when(aiService.searchPolicyVersion(any()))
                .thenReturn(answer(ids.get("2.6"), ids.get("3.2"), List.of("RERANK_UNAVAILABLE")));

        JsonNode body = uploader.bodyOf(search(uploader, Map.of("query", "cataract")));

        assertThat(body.get("degraded").get(0).asString()).isEqualTo("RERANK_UNAVAILABLE");
        assertThat(body.get("rerankModel").isNull()).isTrue();
    }

    @Test
    void aResultFromAnotherVersionIsNeverShown() throws Exception {
        publishAndIndex();
        Map<String, UUID> ids = provisionIds();
        when(aiService.searchPolicyVersion(any())).thenReturn(answer(UUID.randomUUID(), ids.get("3.2"), List.of()));

        MockHttpServletResponse refused = search(uploader, Map.of("query", "cataract"));

        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(refused.getContentAsString()).doesNotContain("Cataract surgery text");
    }

    @Test
    void aSearchFailureIsReportedAndItsUsageKept() throws Exception {
        publishAndIndex();
        ModelRun failed = new ModelRun("embedding", "m", null, "none", 1, "UNAVAILABLE", null, null, null, 30);
        when(aiService.searchPolicyVersion(any())).thenThrow(new Failure("knowledge_store_unavailable", true, List.of(failed)));

        MockHttpServletResponse response = search(uploader, Map.of("query", "cataract"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(uploader.bodyOf(response).get("code").asString()).isEqualTo("search_unavailable");
        assertThat(jdbc.sql("SELECT outcome FROM core.ai_runs WHERE version_id = ? ORDER BY id").param(versionId()).query(String.class).list())
                .containsExactly("SUCCEEDED", "UNAVAILABLE");
    }

    @Test
    void invalidSearchesAreRefusedBeforeAnyModelCall() throws Exception {
        publishAndIndex();

        assertThat(search(uploader, Map.of("query", " a ")).getStatus()).isEqualTo(422);
        assertThat(search(uploader, Map.of("query", "")).getStatus()).isEqualTo(400);
        assertThat(search(uploader, Map.of("query", "x".repeat(501))).getStatus()).isEqualTo(400);
        assertThat(search(uploader, Map.of("query", "cataract", "types", List.of("NOT_A_TYPE"))).getStatus()).isEqualTo(422);
        verify(aiService, never()).searchPolicyVersion(any());
    }

    @Test
    void administratorsAndAnonymousCallersAreRefused() throws Exception {
        assertThat(search(signedInAs(Role.ADMIN), Map.of("query", "cataract")).getStatus()).isEqualTo(403);
        assertThat(signedInAs(Role.ADMIN).getPath(versionPath() + "/search-index").getStatus()).isEqualTo(403);
        assertThat(anonymous().getPath(versionPath() + "/search-index").getStatus()).isEqualTo(401);
    }

    @Test
    void aVersionOfAnotherPolicyIsNotFound() throws Exception {
        String other = fixtures.createPolicy();

        assertThat(uploader.getPath("/api/v1/policies/" + other + "/versions/" + version + "/search-index").getStatus())
                .isEqualTo(404);
        assertThat(uploader.postJson("/api/v1/policies/" + other + "/versions/" + version + "/search", Map.of("query", "x y"))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void indexRecordsCannotBeDeletedByTheRuntimeRole() throws Exception {
        publishAndIndex();

        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DELETE FROM core.policy_search_indexes"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}
