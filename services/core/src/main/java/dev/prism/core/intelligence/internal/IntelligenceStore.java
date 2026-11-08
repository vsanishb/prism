package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.DocumentExtraction.Field;
import dev.prism.core.aiclient.DocumentExtraction.LineItem;
import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import dev.prism.core.aiclient.DocumentExtraction.Result;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.DocumentLineItem;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Finding;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Source;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Writes immutable derived records. Inserts only: the runtime role cannot update or delete these. */
@Repository
class IntelligenceStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    IntelligenceStore(JdbcClient jdbc, JdbcTemplate template, JsonMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    Set<UUID> extractedDocuments(UUID claimId) {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT document_id FROM core.document_extractions WHERE claim_id = ?")
                .param(claimId)
                .query(UUID.class)
                .list());
    }

    /** The newest result for a document, across pipeline versions. */
    Optional<Result> latestExtraction(UUID documentId) {
        return jdbc.sql("""
                        SELECT result::text FROM core.document_extractions
                        WHERE document_id = ? ORDER BY id DESC LIMIT 1
                        """)
                .param(documentId)
                .query(String.class)
                .optional()
                .map(stored -> json.readValue(stored, Result.class));
    }

    void saveExtraction(UUID claimId, UUID documentId, String declaredType, Result result) {
        // Usage belongs in ai_runs. The stored result is the document's content alone.
        Result content = new Result(
                result.pipelineVersion(), result.pageCount(), result.classification(), result.fields(), result.lineItems(), List.of());
        jdbc.sql("""
                        INSERT INTO core.document_extractions (document_id, claim_id, pipeline_version, declared_type,
                            classified_type, classification_confidence, result)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (document_id, pipeline_version) DO NOTHING
                        """)
                .params(
                        documentId,
                        claimId,
                        result.pipelineVersion(),
                        declaredType,
                        result.classification().documentType(),
                        result.classification().confidence(),
                        json.writeValueAsString(content))
                .update();
    }

    void saveRuns(UUID claimId, UUID documentId, List<ModelRun> runs) {
        for (ModelRun run : runs) {
            jdbc.sql("""
                            INSERT INTO core.ai_runs (claim_id, document_id, task, model, provider, prompt_version, attempt,
                                outcome, input_tokens, output_tokens, cost_usd, latency_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            claimId, documentId, run.task(), run.model(), run.provider(), run.promptVersion(), run.attempt(),
                            run.outcome(), run.inputTokens(), run.outputTokens(), run.costUsd(), run.latencyMs())
                    .update();
        }
    }

    /** Runs inside the caller's transaction, so that a consolidation is stored whole or not at all. */
    int saveConsolidation(UUID claimId, boolean complete, ConsolidatedClaim claim) {
        int sequence = jdbc.sql("SELECT coalesce(max(sequence), 0) + 1 FROM core.claim_consolidations WHERE claim_id = ?")
                .param(claimId)
                .query(Integer.class)
                .single();
        UUID consolidation = jdbc.sql("""
                        INSERT INTO core.claim_consolidations (claim_id, sequence, complete)
                        VALUES (?, ?, ?) RETURNING id
                        """)
                .params(claimId, sequence, complete)
                .query(UUID.class)
                .single();
        claim.facts().forEach(fact -> saveFact(consolidation, fact));
        claim.lineItems().forEach(item -> saveLineItem(consolidation, item));
        claim.findings().forEach(finding -> saveFinding(consolidation, finding));
        return sequence;
    }

    private void saveFact(UUID consolidation, Fact fact) {
        UUID factId = jdbc.sql("""
                        INSERT INTO core.claim_facts (consolidation_id, field_name, kind, critical, document_id, state,
                            value, amount, correction_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(
                        consolidation, fact.name(), fact.kind(), fact.critical(), fact.documentId(), fact.state(),
                        fact.value(), fact.amount(), fact.correctionId())
                .query(UUID.class)
                .single();
        fact.sources().forEach(source -> saveSource(factId, source));
    }

    private void saveSource(UUID factId, Source source) {
        Field field = source.field();
        template.update(
                connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO core.claim_fact_sources (fact_id, document_id, state, confidence, raw_value, value,
                                page, quote, region, grounding, reason)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    statement.setObject(1, factId);
                    statement.setObject(2, source.documentId());
                    statement.setString(3, field.state());
                    statement.setString(4, field.confidence());
                    statement.setString(5, field.rawValue());
                    statement.setString(6, field.value());
                    statement.setObject(7, field.page());
                    statement.setString(8, field.quote());
                    statement.setArray(9, region(connection, field.region()));
                    statement.setString(10, field.grounding());
                    statement.setString(11, field.reason());
                    return statement;
                });
    }

    private void saveLineItem(UUID consolidation, DocumentLineItem documentItem) {
        LineItem item = documentItem.item();
        template.update(
                connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO core.claim_line_items (consolidation_id, document_id, position, description, quantity,
                                unit_price, amount, raw_amount, state, page, quote, region, grounding)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    statement.setObject(1, consolidation);
                    statement.setObject(2, documentItem.documentId());
                    statement.setInt(3, documentItem.position());
                    statement.setString(4, item.description());
                    statement.setString(5, item.quantity());
                    statement.setString(6, item.unitPrice());
                    statement.setBigDecimal(7, item.amount());
                    statement.setString(8, item.rawAmount());
                    statement.setString(9, item.state());
                    statement.setInt(10, item.page());
                    statement.setString(11, item.quote());
                    statement.setArray(12, region(connection, item.region()));
                    statement.setString(13, item.grounding());
                    return statement;
                });
    }

    private void saveFinding(UUID consolidation, Finding finding) {
        jdbc.sql("""
                        INSERT INTO core.validation_findings (consolidation_id, code, severity, field_name, document_id,
                            expected_value, actual_value)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        consolidation, finding.code(), finding.severity().name(), finding.fieldName(), finding.documentId(),
                        finding.expectedValue(), finding.actualValue())
                .update();
    }

    private static Array region(Connection connection, List<BigDecimal> region) throws SQLException {
        return region == null ? null : connection.createArrayOf("numeric", region.toArray());
    }
}
