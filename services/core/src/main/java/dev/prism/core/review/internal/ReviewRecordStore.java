package dev.prism.core.review.internal;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notes, information requests, investigation findings, and outcomes. Only requests change state. */
@Repository
class ReviewRecordStore {

    private final JdbcClient jdbc;

    ReviewRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Notes ---

    record Note(UUID id, UUID taskId, String author, String body, Instant createdAt) {}

    void addNote(UUID claimId, UUID taskId, UUID author, String body) {
        jdbc.sql("INSERT INTO core.review_notes (claim_id, task_id, author_id, body) VALUES (?, ?, ?, ?)")
                .params(claimId, taskId, author, body)
                .update();
    }

    List<Note> notes(UUID claimId) {
        return jdbc.sql("""
                        SELECT n.id, n.task_id, u.full_name, n.body, n.created_at FROM core.review_notes n
                        JOIN core.users u ON u.id = n.author_id WHERE n.claim_id = ? ORDER BY n.id
                        """)
                .param(claimId)
                .query((row, index) -> new Note(row.getObject("id", UUID.class), row.getObject("task_id", UUID.class),
                        row.getString("full_name"), row.getString("body"), instant(row.getObject("created_at", OffsetDateTime.class))))
                .list();
    }

    // --- Information requests ---

    record Request(UUID id, List<String> documentTypes, String message, String status, String requestedBy, Instant createdAt,
            Instant closedAt) {}

    UUID request(UUID claimId, UUID taskId, UUID requestedBy, List<String> documentTypes, String message) {
        return jdbc.sql("""
                        INSERT INTO core.information_requests (claim_id, task_id, requested_by, document_types, message)
                        VALUES (?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(claimId, taskId, requestedBy, documentTypes.toArray(String[]::new), message)
                .query(UUID.class)
                .single();
    }

    Optional<UUID> lockOpenRequest(UUID claimId) {
        return jdbc.sql("SELECT id FROM core.information_requests WHERE claim_id = ? AND status = 'OPEN' FOR UPDATE")
                .param(claimId)
                .query(UUID.class)
                .optional();
    }

    void closeRequest(UUID requestId, String status, UUID closedBy) {
        jdbc.sql("UPDATE core.information_requests SET status = ?, closed_at = now(), closed_by = ? WHERE id = ? AND status = 'OPEN'")
                .params(status, closedBy, requestId)
                .update();
    }

    List<Request> requests(UUID claimId) {
        return jdbc.sql("""
                        SELECT r.id, r.document_types, r.message, r.status, u.full_name, r.created_at, r.closed_at
                        FROM core.information_requests r JOIN core.users u ON u.id = r.requested_by
                        WHERE r.claim_id = ? ORDER BY r.id
                        """)
                .param(claimId)
                .query((row, index) -> new Request(row.getObject("id", UUID.class), texts(row.getArray("document_types")),
                        row.getString("message"), row.getString("status"), row.getString("full_name"),
                        instant(row.getObject("created_at", OffsetDateTime.class)),
                        instant(row.getObject("closed_at", OffsetDateTime.class))))
                .list();
    }

    // --- Investigation findings ---

    record FindingRecord(UUID id, UUID taskId, String finding, String rationale, String investigator, Instant createdAt) {}

    void addFinding(UUID claimId, UUID taskId, UUID investigator, String finding, String rationale) {
        jdbc.sql("""
                        INSERT INTO core.investigation_findings (claim_id, task_id, investigator_id, finding, rationale)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(claimId, taskId, investigator, finding, rationale)
                .update();
    }

    boolean investigated(UUID claimId) {
        return jdbc.sql("SELECT count(*) > 0 FROM core.investigation_findings WHERE claim_id = ?")
                .param(claimId)
                .query(Boolean.class)
                .single();
    }

    List<FindingRecord> findings(UUID claimId) {
        return jdbc.sql("""
                        SELECT f.id, f.task_id, f.finding, f.rationale, u.full_name, f.created_at FROM core.investigation_findings f
                        JOIN core.users u ON u.id = f.investigator_id WHERE f.claim_id = ? ORDER BY f.id
                        """)
                .param(claimId)
                .query((row, index) -> new FindingRecord(row.getObject("id", UUID.class), row.getObject("task_id", UUID.class),
                        row.getString("finding"), row.getString("rationale"), row.getString("full_name"),
                        instant(row.getObject("created_at", OffsetDateTime.class))))
                .list();
    }

    // --- Outcomes ---

    record Outcome(
            UUID id,
            UUID taskId,
            String decision,
            BigDecimal approvedAmount,
            String currency,
            String rationale,
            String aiAgreement,
            UUID consolidationId,
            UUID policyAssessmentId,
            UUID riskAssessmentId,
            UUID decidedById,
            String decidedBy,
            Instant decidedAt,
            UUID supersedesId,
            boolean current) {}

    record NewOutcome(
            UUID claimId,
            UUID taskId,
            String decision,
            BigDecimal approvedAmount,
            String currency,
            String rationale,
            String aiAgreement,
            UUID consolidationId,
            UUID policyAssessmentId,
            UUID riskAssessmentId,
            UUID decidedBy,
            UUID supersedesId) {}

    UUID addOutcome(NewOutcome outcome) {
        return jdbc.sql("""
                        INSERT INTO core.authorized_outcomes (claim_id, task_id, decision, approved_amount, currency, rationale,
                            ai_agreement, consolidation_id, policy_assessment_id, risk_assessment_id, decided_by, supersedes_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(outcome.claimId(), outcome.taskId(), outcome.decision(), outcome.approvedAmount(), outcome.currency(),
                        outcome.rationale(), outcome.aiAgreement(), outcome.consolidationId(), outcome.policyAssessmentId(),
                        outcome.riskAssessmentId(), outcome.decidedBy(), outcome.supersedesId())
                .query(UUID.class)
                .single();
    }

    /** Every outcome, oldest first. The current one is the one nothing supersedes. */
    List<Outcome> outcomes(UUID claimId) {
        return jdbc.sql("""
                        SELECT o.*, u.full_name,
                            NOT EXISTS (SELECT 1 FROM core.authorized_outcomes later WHERE later.supersedes_id = o.id) AS current
                        FROM core.authorized_outcomes o JOIN core.users u ON u.id = o.decided_by
                        WHERE o.claim_id = ? ORDER BY o.id
                        """)
                .param(claimId)
                .query((row, index) -> new Outcome(
                        row.getObject("id", UUID.class),
                        row.getObject("task_id", UUID.class),
                        row.getString("decision"),
                        row.getBigDecimal("approved_amount"),
                        row.getString("currency"),
                        row.getString("rationale"),
                        row.getString("ai_agreement"),
                        row.getObject("consolidation_id", UUID.class),
                        row.getObject("policy_assessment_id", UUID.class),
                        row.getObject("risk_assessment_id", UUID.class),
                        row.getObject("decided_by", UUID.class),
                        row.getString("full_name"),
                        instant(row.getObject("decided_at", OffsetDateTime.class)),
                        row.getObject("supersedes_id", UUID.class),
                        row.getBoolean("current")))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
