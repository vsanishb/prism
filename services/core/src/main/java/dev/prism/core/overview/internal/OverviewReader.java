package dev.prism.core.overview.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures on the home page. Read-only counts over claims, their latest screening, open review work,
 * and current outcomes. Drafts are counted only for staff who may see them, as the claim list does.
 */
@Service
class OverviewReader {

    private static final int RECENT = 6;
    private static final List<String> OPEN = List.of("SUBMITTED", "PROCESSING", "AWAITING_INFORMATION", "IN_REVIEW", "ESCALATED");

    private final JdbcClient jdbc;

    OverviewReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Amount(String currency, BigDecimal amount) {}

    record RecentClaim(
            UUID id,
            String claimNumber,
            String patientName,
            String hospitalName,
            String status,
            LocalDate admissionDate,
            BigDecimal claimedAmount,
            String currency,
            String riskBand,
            Instant createdAt) {}

    /**
     * @param riskBands the latest screening of each claim still in the system's hands or decided
     * @param openTasks review tasks waiting or being worked, by queue
     * @param outcomes current outcomes by decision; a revised outcome counts once, as its revision
     */
    record Overview(
            Map<String, Long> claimsByStatus,
            Map<String, Long> riskBands,
            Map<String, Long> openTasks,
            Map<String, Long> outcomes,
            List<Amount> openClaimed,
            List<Amount> approved,
            List<RecentClaim> recent) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public Overview read() {
        boolean drafts = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "CLAIM_MANAGE".equals(authority.getAuthority()));
        return new Overview(
                counts("SELECT status, count(*) FROM core.claims WHERE status <> 'DRAFT' OR :drafts GROUP BY status", drafts),
                counts("""
                        SELECT band, count(*) FROM (
                            SELECT DISTINCT ON (r.claim_id) r.band
                            FROM core.risk_assessments r JOIN core.claims c ON c.id = r.claim_id
                            WHERE c.status NOT IN ('DRAFT', 'WITHDRAWN')
                            ORDER BY r.claim_id, r.id DESC) latest
                        GROUP BY band
                        """, drafts),
                counts("SELECT queue, count(*) FROM core.review_tasks WHERE status IN ('OPEN', 'CLAIMED') GROUP BY queue", drafts),
                counts("""
                        SELECT o.decision, count(*) FROM core.authorized_outcomes o
                        WHERE NOT EXISTS (SELECT 1 FROM core.authorized_outcomes later WHERE later.supersedes_id = o.id)
                        GROUP BY o.decision
                        """, drafts),
                jdbc.sql("SELECT currency, sum(claimed_amount) FROM core.claims WHERE status IN (:open) GROUP BY currency ORDER BY currency")
                        .param("open", OPEN)
                        .query((row, index) -> new Amount(row.getString(1), row.getBigDecimal(2)))
                        .list(),
                jdbc.sql("""
                        SELECT o.currency, sum(o.approved_amount) FROM core.authorized_outcomes o
                        WHERE o.approved_amount IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM core.authorized_outcomes later WHERE later.supersedes_id = o.id)
                        GROUP BY o.currency ORDER BY o.currency
                        """)
                        .query((row, index) -> new Amount(row.getString(1), row.getBigDecimal(2)))
                        .list(),
                jdbc.sql("""
                        SELECT c.id, c.claim_number, c.patient_name, c.hospital_name, c.status, c.admission_date,
                               c.claimed_amount, c.currency,
                               (SELECT r.band FROM core.risk_assessments r WHERE r.claim_id = c.id ORDER BY r.id DESC LIMIT 1),
                               c.created_at
                        FROM core.claims c
                        WHERE c.status <> 'DRAFT' OR :drafts
                        ORDER BY c.created_at DESC, c.id DESC
                        LIMIT :limit
                        """)
                        .param("drafts", drafts)
                        .param("limit", RECENT)
                        .query((row, index) -> new RecentClaim(
                                row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4),
                                row.getString(5), row.getObject(6, LocalDate.class), row.getBigDecimal(7), row.getString(8),
                                row.getString(9), row.getTimestamp(10).toInstant()))
                        .list());
    }

    private Map<String, Long> counts(String sql, boolean drafts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        var statement = jdbc.sql(sql);
        if (sql.contains(":drafts")) {
            statement = statement.param("drafts", drafts);
        }
        statement.query((row, index) -> Map.entry(row.getString(1), row.getLong(2)))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }
}
