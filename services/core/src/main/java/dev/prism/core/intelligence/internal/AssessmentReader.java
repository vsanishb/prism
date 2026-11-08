package dev.prism.core.intelligence.internal;

import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.policyknowledge.PublishedKnowledge;
import dev.prism.core.policyknowledge.PublishedKnowledge.Provision;
import dev.prism.core.policyknowledge.PublishedKnowledge.Snapshot;
import dev.prism.core.policyknowledge.PublishedKnowledge.Value;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The latest policy analysis of a claim, joined to its facts and to the wording it cites. */
@Service
class AssessmentReader {

    private final ClaimDirectory claims;
    private final PublishedKnowledge knowledge;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    AssessmentReader(ClaimDirectory claims, PublishedKnowledge knowledge, JdbcClient jdbc, JsonMapper json) {
        this.claims = claims;
        this.knowledge = knowledge;
        this.jdbc = jdbc;
        this.json = json;
    }

    record AssessmentResponse(AssessmentView assessment) {}

    record AssessmentView(
            UUID id,
            UUID versionId,
            UUID wordingDocumentId,
            LocalDate coverStart,
            Instant createdAt,
            boolean current,
            int degradedSearches,
            String pipelineVersion,
            List<FindingView> findings) {}

    record FindingView(
            int position,
            String kind,
            String question,
            String status,
            String proposedStatus,
            String reasoning,
            List<String> unresolved,
            List<String> problems,
            int rounds,
            List<String> queries,
            List<FactView> facts,
            List<CitationView> citations,
            List<CalculationView> calculations) {}

    record FactView(UUID id, String name, String state, String value) {}

    record CitationView(
            UUID provisionId, String key, String title, String type, int pageStart, String quote, List<BigDecimal> region) {}

    record CalculationView(
            String role,
            String valueName,
            List<List<String>> inputs,
            List<String> steps,
            BigDecimal resultAmount,
            LocalDate resultDate,
            String outcome) {}

    private record Header(UUID id, UUID consolidationId, UUID versionId, LocalDate coverStart, Instant createdAt,
            int degradedSearches, String pipelineVersion) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public AssessmentResponse latest(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        Optional<Header> header = jdbc.sql("""
                        SELECT * FROM core.policy_assessments WHERE claim_id = ? ORDER BY id DESC LIMIT 1
                        """)
                .param(claim.id())
                .query((row, index) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getObject("consolidation_id", UUID.class),
                        row.getObject("version_id", UUID.class),
                        row.getObject("cover_start", LocalDate.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getInt("degraded_searches"),
                        row.getString("pipeline_version")))
                .optional();
        if (header.isEmpty()) {
            return new AssessmentResponse(null);
        }
        Header found = header.get();
        Optional<Snapshot> snapshot = knowledge.snapshot(found.versionId());
        Map<UUID, Provision> provisions = snapshot.map(Snapshot::provisions).orElse(List.of()).stream()
                .collect(Collectors.toMap(Provision::id, Function.identity()));
        Map<UUID, Value> values = snapshot.map(Snapshot::values).orElse(List.of()).stream()
                .collect(Collectors.toMap(Value::id, Function.identity()));
        UUID latestConsolidation = jdbc.sql(
                        "SELECT id FROM core.claim_consolidations WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1")
                .param(claim.id())
                .query(UUID.class)
                .single();
        List<FindingView> findings = jdbc.sql("SELECT * FROM core.assessment_findings WHERE assessment_id = ? ORDER BY position")
                .param(found.id())
                .query((row, index) -> finding(row, provisions, values))
                .list();
        return new AssessmentResponse(new AssessmentView(
                found.id(),
                found.versionId(),
                snapshot.map(Snapshot::documentId).orElse(null),
                found.coverStart(),
                found.createdAt(),
                latestConsolidation.equals(found.consolidationId()),
                found.degradedSearches(),
                found.pipelineVersion(),
                findings));
    }

    private FindingView finding(ResultSet row, Map<UUID, Provision> provisions, Map<UUID, Value> values) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        return new FindingView(
                row.getInt("position"),
                row.getString("kind"),
                row.getString("question"),
                row.getString("status"),
                row.getString("proposed_status"),
                row.getString("reasoning"),
                texts(row.getArray("unresolved")),
                texts(row.getArray("problems")),
                row.getInt("rounds"),
                texts(row.getArray("queries")),
                jdbc.sql("""
                                SELECT f.id, f.field_name, f.state, f.value FROM core.assessment_facts a
                                JOIN core.claim_facts f ON f.id = a.fact_id WHERE a.finding_id = ? ORDER BY f.field_name
                                """)
                        .param(id)
                        .query((fact, index) -> new FactView(
                                fact.getObject("id", UUID.class), fact.getString("field_name"), fact.getString("state"),
                                fact.getString("value")))
                        .list(),
                jdbc.sql("SELECT provision_id, quote FROM core.assessment_citations WHERE finding_id = ? ORDER BY id")
                        .param(id)
                        .query((citation, index) -> {
                            Provision provision = provisions.get(citation.getObject("provision_id", UUID.class));
                            return new CitationView(
                                    citation.getObject("provision_id", UUID.class),
                                    provision == null ? null : provision.key(),
                                    provision == null ? "" : provision.title(),
                                    provision == null ? null : provision.type(),
                                    provision == null ? 1 : provision.pageStart(),
                                    citation.getString("quote"),
                                    provision == null ? null : provision.region());
                        })
                        .list(),
                jdbc.sql("SELECT * FROM core.assessment_calculations WHERE finding_id = ? ORDER BY id")
                        .param(id)
                        .query((calculation, index) -> {
                            Value value = values.get(calculation.getObject("value_id", UUID.class));
                            return new CalculationView(
                                    calculation.getString("role"),
                                    value == null ? null : value.name(),
                                    json.readValue(calculation.getString("inputs"), new TypeReference<List<List<String>>>() {}),
                                    texts(calculation.getArray("steps")),
                                    calculation.getBigDecimal("result_amount"),
                                    calculation.getObject("result_date", LocalDate.class),
                                    calculation.getString("outcome"));
                        })
                        .list());
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
