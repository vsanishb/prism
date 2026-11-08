package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import dev.prism.core.aiclient.PolicyAnalysis;
import dev.prism.core.intelligence.internal.AssessmentCalculator.Calculation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Reads the facts an analysis starts from, and stores its result. Insert-only. */
@Repository
class AssessmentStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    AssessmentStore(JdbcClient jdbc, JdbcTemplate template, JsonMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    record Fact(UUID id, String name, String kind, String state, String value, BigDecimal amount) {}

    record LineItem(UUID id, String description, BigDecimal amount) {}

    record LatestFacts(UUID consolidationId, List<Fact> facts, List<LineItem> lineItems) {}

    /** The newest consolidation's claim-level facts, and its line items read with confidence. */
    Optional<LatestFacts> latestFacts(UUID claimId) {
        return jdbc.sql("SELECT id FROM core.claim_consolidations WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1")
                .param(claimId)
                .query(UUID.class)
                .optional()
                .map(consolidation -> new LatestFacts(
                        consolidation,
                        jdbc.sql("""
                                        SELECT id, field_name, kind, state, value, amount FROM core.claim_facts
                                        WHERE consolidation_id = ? AND document_id IS NULL ORDER BY field_name
                                        """)
                                .param(consolidation)
                                .query((row, index) -> new Fact(
                                        row.getObject("id", UUID.class),
                                        row.getString("field_name"),
                                        row.getString("kind"),
                                        row.getString("state"),
                                        row.getString("value"),
                                        row.getBigDecimal("amount")))
                                .list(),
                        jdbc.sql("""
                                        SELECT id, description, amount FROM core.claim_line_items
                                        WHERE consolidation_id = ? AND state = 'PRESENT' ORDER BY document_id, position
                                        """)
                                .param(consolidation)
                                .query((row, index) -> new LineItem(
                                        row.getObject("id", UUID.class), row.getString("description"), row.getBigDecimal("amount")))
                                .list()));
    }

    boolean analyzed(UUID consolidationId) {
        return jdbc.sql("SELECT count(*) > 0 FROM core.policy_assessments WHERE consolidation_id = ?")
                .param(consolidationId)
                .query(Boolean.class)
                .single();
    }

    record CheckedFinding(PolicyAnalysis.Finding finding, String status, List<String> problems, List<String> unresolved,
            List<PolicyAnalysis.Citation> citations, List<CalculatedValue> calculations) {}

    record CalculatedValue(UUID valueId, Calculation calculation) {}

    UUID save(UUID claimId, UUID consolidationId, UUID versionId, LocalDate coverStart, PolicyAnalysis.Result result,
            List<CheckedFinding> findings) {
        UUID assessment = jdbc.sql("""
                        INSERT INTO core.policy_assessments (claim_id, consolidation_id, version_id, pipeline_version,
                            plan_prompt_version, analysis_prompt_version, cover_start, degraded_searches)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(claimId, consolidationId, versionId, result.pipelineVersion(), result.planPromptVersion(),
                        result.analysisPromptVersion(), coverStart, result.degradedSearches())
                .query(UUID.class)
                .single();
        for (CheckedFinding checked : findings) {
            PolicyAnalysis.Finding finding = checked.finding();
            UUID findingId = template.execute((Connection connection) -> {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO core.assessment_findings (assessment_id, position, kind, question, status,
                            proposed_status, reasoning, unresolved, problems, rounds, queries)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)) {
                    statement.setObject(1, assessment);
                    statement.setInt(2, finding.position());
                    statement.setString(3, finding.kind());
                    statement.setString(4, finding.question());
                    statement.setString(5, checked.status());
                    // What the model first proposed, if validation here or in the AI service changed it.
                    String proposed = finding.proposedStatus() != null ? finding.proposedStatus() : finding.status();
                    statement.setString(6, checked.status().equals(proposed) ? null : proposed);
                    statement.setString(7, finding.reasoning());
                    statement.setArray(8, texts(connection, checked.unresolved()));
                    statement.setArray(9, texts(connection, checked.problems()));
                    statement.setInt(10, finding.rounds());
                    statement.setArray(11, texts(connection, finding.queries()));
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        return rows.getObject(1, UUID.class);
                    }
                }
            });
            for (PolicyAnalysis.Citation citation : checked.citations()) {
                jdbc.sql("INSERT INTO core.assessment_citations (finding_id, provision_id, quote) VALUES (?, ?, ?)")
                        .params(findingId, citation.provisionId(), citation.quote())
                        .update();
            }
            for (UUID fact : finding.factsUsed().stream().distinct().toList()) {
                jdbc.sql("INSERT INTO core.assessment_facts (finding_id, fact_id) VALUES (?, ?)").params(findingId, fact).update();
            }
            for (CalculatedValue calculated : checked.calculations()) {
                Calculation calculation = calculated.calculation();
                template.execute((Connection connection) -> {
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO core.assessment_calculations (finding_id, value_id, role, inputs, steps,
                                result_amount, result_date, outcome)
                            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                            """)) {
                        statement.setObject(1, findingId);
                        statement.setObject(2, calculated.valueId());
                        statement.setString(3, calculation.role());
                        // Pairs, not an object: jsonb does not keep key order, and the order reads as a sum.
                        statement.setString(4, json.writeValueAsString(calculation.inputs().entrySet().stream()
                                .map(input -> List.of(input.getKey(), input.getValue()))
                                .toList()));
                        statement.setArray(5, texts(connection, calculation.steps()));
                        statement.setBigDecimal(6, calculation.resultAmount());
                        statement.setObject(7, calculation.resultDate());
                        statement.setString(8, calculation.outcome());
                        return statement.executeUpdate();
                    }
                });
            }
        }
        return assessment;
    }

    void saveRuns(UUID claimId, List<ModelRun> runs) {
        for (ModelRun run : runs) {
            jdbc.sql("""
                            INSERT INTO core.ai_runs (claim_id, task, model, provider, prompt_version, attempt,
                                outcome, input_tokens, output_tokens, cost_usd, latency_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(claimId, run.task(), run.model(), run.provider(), run.promptVersion(), run.attempt(),
                            run.outcome(), run.inputTokens(), run.outputTokens(), run.costUsd(), run.latencyMs())
                    .update();
        }
    }

    private static java.sql.Array texts(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }
}
