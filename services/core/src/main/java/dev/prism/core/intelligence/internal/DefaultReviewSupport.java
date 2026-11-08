package dev.prism.core.intelligence.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.intelligence.ReviewSupport;
import dev.prism.core.platform.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultReviewSupport implements ReviewSupport {

    private static final int MAX_TEXT = 500;

    private final JdbcClient jdbc;
    private final FactCorrectionStore corrections;
    private final AuditRecorder audit;

    DefaultReviewSupport(JdbcClient jdbc, FactCorrectionStore corrections, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.audit = audit;
    }

    private record Latest(UUID id, int sequence, Instant createdAt) {}

    private Optional<Latest> latest(UUID claimId) {
        return jdbc.sql("""
                        SELECT id, sequence, created_at FROM core.claim_consolidations
                        WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1
                        """)
                .param(claimId)
                .query((row, index) -> new Latest(row.getObject("id", UUID.class), row.getInt("sequence"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentRecords current(UUID claimId) {
        Optional<Latest> latest = latest(claimId);
        UUID assessment = jdbc.sql("SELECT id FROM core.policy_assessments WHERE claim_id = ? ORDER BY id DESC LIMIT 1")
                .param(claimId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        record Risk(UUID id, String band) {}
        Optional<Risk> risk = jdbc.sql("SELECT id, band FROM core.risk_assessments WHERE claim_id = ? ORDER BY id DESC LIMIT 1")
                .param(claimId)
                .query((row, index) -> new Risk(row.getObject("id", UUID.class), row.getString("band")))
                .optional();
        return new CurrentRecords(
                latest.map(Latest::id).orElse(null),
                latest.map(Latest::sequence).orElse(null),
                assessment,
                risk.map(Risk::id).orElse(null),
                risk.map(Risk::band).orElse(null));
    }

    private record CurrentFact(UUID id, String name, String kind, UUID documentId, String value) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID correctFact(UUID claimId, UUID factId, String value, String reason, UUID correctedBy) {
        UUID consolidation = latest(claimId).map(Latest::id)
                .orElseThrow(() -> ApiException.conflict("no_facts", "This claim has no facts to correct yet."));
        CurrentFact fact = jdbc.sql("""
                        SELECT id, field_name, kind, document_id, value FROM core.claim_facts
                        WHERE id = ? AND consolidation_id = ?
                        """)
                .params(factId, consolidation)
                .query((row, index) -> new CurrentFact(row.getObject("id", UUID.class), row.getString("field_name"),
                        row.getString("kind"), row.getObject("document_id", UUID.class), row.getString("value")))
                .optional()
                .orElseThrow(() -> ApiException.conflict("fact_not_current", "This fact is not in the claim's latest facts. Reload."));
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.length() < 5) {
            throw ApiException.invalid("reason_required", "Say why the value is corrected.");
        }
        String clean = value == null ? "" : value.strip();
        BigDecimal amount = null;
        switch (fact.kind()) {
            case "DATE" -> {
                try {
                    clean = LocalDate.parse(clean).toString();
                } catch (DateTimeParseException failure) {
                    throw ApiException.invalid("invalid_date", "Enter the date as YYYY-MM-DD.");
                }
            }
            case "AMOUNT" -> {
                try {
                    amount = new BigDecimal(clean.replace(",", "")).setScale(2, RoundingMode.UNNECESSARY);
                } catch (NumberFormatException | ArithmeticException failure) {
                    throw ApiException.invalid("invalid_amount", "Enter the amount as a number, with at most two decimals.");
                }
                if (amount.signum() <= 0) {
                    throw ApiException.invalid("invalid_amount", "Enter an amount above zero.");
                }
                clean = amount.toPlainString();
            }
            default -> {
                if (clean.isEmpty() || clean.length() > MAX_TEXT) {
                    throw ApiException.invalid("invalid_value", "Enter a value of up to 500 characters.");
                }
            }
        }
        if (clean.equals(fact.value())) {
            throw ApiException.invalid("unchanged", "The corrected value is the same as the current one.");
        }
        UUID id = corrections.insert(claimId, fact.name(), fact.documentId(), fact.kind(), clean, amount, cleanReason, fact.id(),
                correctedBy);
        // The field is recorded, never the value: values can be personal or health data.
        audit.record(AuditEntry.of("claim:" + claimId, AuditAction.CLAIM_FACT_CORRECTED, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId)
                .detail(AuditDetail.FIELD, fact.name()));
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Correction> corrections(UUID claimId) {
        UUID consolidation = latest(claimId).map(Latest::id).orElse(null);
        return jdbc.sql("""
                        SELECT c.id, c.field_name, c.document_id, f.value AS original, c.value, c.reason, u.full_name, c.created_at,
                            EXISTS (SELECT 1 FROM core.claim_facts applied
                                    WHERE applied.correction_id = c.id AND applied.consolidation_id = ?) AS applied
                        FROM core.fact_corrections c
                        JOIN core.claim_facts f ON f.id = c.based_on_fact
                        JOIN core.users u ON u.id = c.corrected_by
                        WHERE c.claim_id = ? ORDER BY c.id DESC
                        """)
                .params(consolidation, claimId)
                .query((row, index) -> new Correction(row.getObject("id", UUID.class), row.getString("field_name"),
                        row.getObject("document_id", UUID.class), row.getString("original"), row.getString("value"),
                        row.getString("reason"), row.getString("full_name"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getBoolean("applied")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Evidence evidence(UUID claimId, UUID consolidationId) {
        Latest consolidation = jdbc.sql("SELECT id, sequence, created_at FROM core.claim_consolidations WHERE id = ? AND claim_id = ?")
                .params(consolidationId, claimId)
                .query((row, index) -> new Latest(row.getObject("id", UUID.class), row.getInt("sequence"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Consolidation not found."));
        return new Evidence(
                consolidation.sequence(),
                consolidation.createdAt(),
                facts(consolidationId),
                assessment(consolidationId),
                screening(consolidationId),
                runs(claimId),
                documents(claimId));
    }

    private List<EvidenceFact> facts(UUID consolidationId) {
        Map<UUID, List<SourceRef>> sources = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.fact_id, s.document_id, s.page, s.quote, s.region FROM core.claim_fact_sources s
                        JOIN core.claim_facts f ON f.id = s.fact_id
                        WHERE f.consolidation_id = ? AND f.document_id IS NULL ORDER BY s.id
                        """)
                .param(consolidationId)
                .query((row, index) -> {
                    sources.computeIfAbsent(row.getObject("fact_id", UUID.class), key -> new ArrayList<>()).add(new SourceRef(
                            row.getObject("document_id", UUID.class), (Integer) row.getObject("page"), row.getString("quote"),
                            decimals(row.getArray("region"))));
                    return null;
                })
                .list();
        return jdbc.sql("""
                        SELECT id, field_name, state, value, correction_id FROM core.claim_facts
                        WHERE consolidation_id = ? AND document_id IS NULL ORDER BY field_name
                        """)
                .param(consolidationId)
                .query((row, index) -> new EvidenceFact(row.getString("field_name"), row.getString("state"), row.getString("value"),
                        row.getObject("correction_id") != null,
                        List.copyOf(sources.getOrDefault(row.getObject("id", UUID.class), List.of()))))
                .list();
    }

    private AssessmentRef assessment(UUID consolidationId) {
        return jdbc.sql("""
                        SELECT a.id, a.pipeline_version, a.created_at, count(f.id) AS findings,
                            count(f.id) FILTER (WHERE f.status = 'SUPPORTED') AS supported
                        FROM core.policy_assessments a LEFT JOIN core.assessment_findings f ON f.assessment_id = a.id
                        WHERE a.consolidation_id = ? GROUP BY a.id
                        """)
                .param(consolidationId)
                .query((row, index) -> new AssessmentRef(row.getObject("id", UUID.class), row.getString("pipeline_version"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getInt("findings"), row.getInt("supported")))
                .optional()
                .orElse(null);
    }

    private ScreeningRef screening(UUID consolidationId) {
        return jdbc.sql("""
                        SELECT a.id, a.band, a.score, a.ruleset_version, a.model_version,
                            (SELECT count(*) FROM core.risk_indicators i WHERE i.assessment_id = a.id) AS indicators
                        FROM core.risk_assessments a WHERE a.consolidation_id = ?
                        """)
                .param(consolidationId)
                .query((row, index) -> new ScreeningRef(row.getObject("id", UUID.class), row.getString("band"), row.getInt("score"),
                        row.getString("ruleset_version"), row.getString("model_version"), row.getInt("indicators")))
                .optional()
                .orElse(null);
    }

    private List<ModelRunRef> runs(UUID claimId) {
        return jdbc.sql("""
                        SELECT task, model, prompt_version, outcome, recorded_at FROM core.ai_runs
                        WHERE claim_id = ? ORDER BY recorded_at, id
                        """)
                .param(claimId)
                .query((row, index) -> new ModelRunRef(row.getString("task"), row.getString("model"),
                        row.getString("prompt_version"), row.getString("outcome"),
                        row.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private List<DocumentRef> documents(UUID claimId) {
        return jdbc.sql("""
                        SELECT id, original_filename, document_type, sha256, uploaded_at FROM core.documents
                        WHERE owner_type = 'CLAIM' AND owner_id = ? AND status = 'ACTIVE' ORDER BY uploaded_at, id
                        """)
                .param(claimId)
                .query((row, index) -> new DocumentRef(row.getObject("id", UUID.class), row.getString("original_filename"),
                        row.getString("document_type"), HexFormat.of().formatHex(row.getBytes("sha256")),
                        row.getObject("uploaded_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static List<BigDecimal> decimals(Array array) throws SQLException {
        return array == null ? null : List.of((BigDecimal[]) array.getArray());
    }
}
