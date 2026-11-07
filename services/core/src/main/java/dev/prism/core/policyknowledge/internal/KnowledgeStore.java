package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.aiclient.PolicyExtraction.Value;
import dev.prism.core.policyknowledge.internal.Assembly.Finding;
import dev.prism.core.policyknowledge.internal.Assembly.Provision;
import dev.prism.core.policyknowledge.internal.Assembly.Relation;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Writes ingestion records. Derived knowledge is insert-only: the runtime role cannot change it. */
@Repository
class KnowledgeStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    KnowledgeStore(JdbcClient jdbc, JdbcTemplate template, JsonMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    record Ingestion(UUID id, UUID versionId, UUID documentId, String status, int pageCount) {}

    Optional<Ingestion> ingestion(UUID documentId) {
        return jdbc.sql("SELECT id, version_id, document_id, status, page_count FROM core.policy_ingestions WHERE document_id = ?")
                .param(documentId)
                .query((row, index) -> new Ingestion(
                        row.getObject("id", UUID.class),
                        row.getObject("version_id", UUID.class),
                        row.getObject("document_id", UUID.class),
                        row.getString("status"),
                        row.getInt("page_count")))
                .optional();
    }

    void createIngestion(UUID versionId, UUID documentId, int pageCount) {
        jdbc.sql("""
                        INSERT INTO core.policy_ingestions (version_id, document_id, page_count) VALUES (?, ?, ?)
                        ON CONFLICT (document_id) DO NOTHING
                        """)
                .params(versionId, documentId, pageCount)
                .update();
    }

    List<Integer> extractedBatchStarts(UUID ingestionId) {
        return jdbc.sql("SELECT first_page FROM core.policy_page_extractions WHERE ingestion_id = ?")
                .param(ingestionId)
                .query(Integer.class)
                .list();
    }

    void recordBatch(UUID ingestionId, int first, int last, String status, String detailCode) {
        jdbc.sql("""
                        INSERT INTO core.policy_batch_events (ingestion_id, first_page, last_page, status, detail_code)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(ingestionId, first, last, status, detailCode)
                .update();
    }

    void saveBatch(UUID ingestionId, PolicyExtraction.Result result, int first, int last) {
        // Usage is stored in ai_runs. This is what the pages said.
        PolicyExtraction.Result content = new PolicyExtraction.Result(
                result.pipelineVersion(), result.pageCount(), result.firstPage(), result.lastPage(), result.provisions(), List.of());
        jdbc.sql("""
                        INSERT INTO core.policy_page_extractions (ingestion_id, first_page, last_page, pipeline_version, result)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (ingestion_id, first_page) DO NOTHING
                        """)
                .params(ingestionId, first, last, result.pipelineVersion(), json.writeValueAsString(content))
                .update();
    }

    void saveRuns(UUID ingestionId, UUID documentId, List<dev.prism.core.aiclient.DocumentExtraction.ModelRun> runs) {
        for (var run : runs) {
            jdbc.sql("""
                            INSERT INTO core.ai_runs (ingestion_id, document_id, task, model, provider, prompt_version, attempt,
                                outcome, input_tokens, output_tokens, cost_usd, latency_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            ingestionId, documentId, run.task(), run.model(), run.provider(), run.promptVersion(), run.attempt(),
                            run.outcome(), run.inputTokens(), run.outputTokens(), run.costUsd(), run.latencyMs())
                    .update();
        }
    }

    List<PolicyExtraction.Result> batches(UUID ingestionId) {
        return jdbc.sql("SELECT result::text FROM core.policy_page_extractions WHERE ingestion_id = ? ORDER BY first_page")
                .param(ingestionId)
                .query(String.class)
                .list()
                .stream()
                .map(stored -> json.readValue(stored, PolicyExtraction.Result.class))
                .toList();
    }

    boolean alreadyAssembled(UUID ingestionId) {
        return jdbc.sql("SELECT count(*) > 0 FROM core.policy_provisions WHERE ingestion_id = ?")
                .param(ingestionId)
                .query(Boolean.class)
                .single();
    }

    /** Runs inside the caller's transaction, so that knowledge is stored whole or not at all. */
    void saveAssembly(UUID ingestionId, Assembly assembly) {
        Map<Provision, UUID> provisionIds = new IdentityHashMap<>();
        Map<Value, UUID> valueIds = new IdentityHashMap<>();
        int position = 0;
        for (Provision provision : assembly.provisions()) {
            UUID id = UUID.randomUUID();
            provisionIds.put(provision, id);
            insertProvision(id, ingestionId, ++position, provision);
            int valuePosition = 0;
            for (Value value : provision.values) {
                UUID valueId = UUID.randomUUID();
                valueIds.put(value, valueId);
                insertValue(valueId, id, ++valuePosition, value);
            }
        }
        for (Relation relation : assembly.relations()) {
            jdbc.sql("""
                            INSERT INTO core.policy_relations (ingestion_id, from_provision_id, target_key, to_provision_id,
                                relation, origin, stated_in_text)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            ingestionId, provisionIds.get(relation.from()), relation.targetKey(),
                            relation.to() == null ? null : provisionIds.get(relation.to()), relation.relation(), relation.origin(),
                            relation.statedInText())
                    .update();
        }
        for (Finding finding : assembly.findings()) {
            jdbc.sql("""
                            INSERT INTO core.policy_findings (ingestion_id, code, severity, provision_id, value_id,
                                expected_value, actual_value)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            ingestionId, finding.code(), finding.severity().name(),
                            finding.provision() == null ? null : provisionIds.get(finding.provision()),
                            finding.value() == null ? null : valueIds.get(finding.value()), finding.expectedValue(),
                            finding.actualValue())
                    .update();
        }
        jdbc.sql("UPDATE core.policy_ingestions SET status = 'IN_REVIEW', completed_at = now() WHERE id = ?")
                .param(ingestionId)
                .update();
    }

    private void insertProvision(UUID id, UUID ingestionId, int position, Provision provision) {
        template.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO core.policy_provisions (id, ingestion_id, position, provision_key, type, title, text,
                        page_start, page_end, quote, region, grounding, text_verified, parent_key, exceptions)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, id);
            statement.setObject(2, ingestionId);
            statement.setInt(3, position);
            statement.setString(4, provision.key);
            statement.setString(5, provision.type);
            statement.setString(6, provision.title);
            statement.setString(7, provision.text);
            statement.setInt(8, provision.pageStart);
            statement.setInt(9, provision.pageEnd);
            statement.setString(10, provision.quote);
            statement.setArray(11, region(connection, provision.region));
            statement.setString(12, provision.grounding);
            statement.setBoolean(13, provision.textVerified);
            statement.setString(14, provision.parentKey);
            statement.setArray(15, connection.createArrayOf("text", provision.exceptions.toArray()));
            return statement;
        });
    }

    private void insertValue(UUID id, UUID provisionId, int position, Value value) {
        boolean numeric = !"TEXT".equals(value.kind()) && value.value() != null;
        template.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO core.policy_values (id, provision_id, position, name, kind, raw_value, value, number, unit,
                        qualifier, state, page, quote, region, grounding, problem, critical)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, id);
            statement.setObject(2, provisionId);
            statement.setInt(3, position);
            statement.setString(4, value.name());
            statement.setString(5, value.kind());
            statement.setString(6, value.rawValue());
            statement.setString(7, value.value());
            statement.setBigDecimal(8, numeric ? new BigDecimal(value.value()) : null);
            statement.setString(9, value.unit());
            statement.setString(10, value.qualifier());
            statement.setString(11, value.state());
            statement.setObject(12, value.page());
            statement.setString(13, value.quote());
            statement.setArray(14, region(connection, value.region()));
            statement.setString(15, value.grounding());
            statement.setString(16, value.problem());
            statement.setBoolean(17, PolicyValidator.isCritical(value));
            return statement;
        });
    }

    private static Array region(Connection connection, List<BigDecimal> region) throws SQLException {
        return region == null ? null : connection.createArrayOf("numeric", region.toArray());
    }
}
