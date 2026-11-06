package dev.prism.core.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads one stream as a business history, such as a claim's activity. It performs no authorization:
 * callers must already have established access to the stream's subject.
 */
@Component
public class AuditTimeline {

    private static final int MAX_ENTRIES = 200;

    private final JdbcClient jdbc;

    public AuditTimeline(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TimelineEntry(Instant occurredAt, String action, String outcome, String actorName) {}

    public List<TimelineEntry> forStream(String stream, Set<AuditAction> excluded) {
        String[] hidden = excluded.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.sql("""
                        SELECT e.occurred_at, e.action, e.outcome, u.full_name
                        FROM core.audit_events e LEFT JOIN core.users u ON u.id = e.actor_id
                        WHERE e.stream = ? AND NOT (e.action = ANY (string_to_array(?, ',')))
                        ORDER BY e.sequence DESC LIMIT ?
                        """)
                .params(stream, String.join(",", hidden), MAX_ENTRIES)
                .query((row, index) -> new TimelineEntry(
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("action"),
                        row.getString("outcome"),
                        row.getString("full_name")))
                .list();
    }
}
