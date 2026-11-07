package dev.prism.core.workflow.internal;

import dev.prism.core.platform.outbox.Outbox;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed events to the workflow engine. Delivery is at least once, so a crash after sending
 * and before marking only repeats a harmless event. SKIP LOCKED lets several instances run safely.
 */
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;

    private final JdbcClient jdbc;
    private final WorkflowDispatcher dispatcher;

    OutboxRelay(JdbcClient jdbc, WorkflowDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
    }

    private record Pending(UUID id, Outbox.Event event, UUID claimId) {}

    @Transactional
    public int publishPending() {
        List<Pending> pending = jdbc.sql("""
                        SELECT id, event_type, aggregate_id FROM core.outbox_events
                        WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                        """)
                .param(BATCH_SIZE)
                .query((row, index) -> new Pending(
                        row.getObject("id", UUID.class),
                        Outbox.Event.valueOf(row.getString("event_type")),
                        row.getObject("aggregate_id", UUID.class)))
                .list();

        // Events for one claim stay in order: a signal must not overtake the start that failed before it.
        Set<UUID> blocked = new HashSet<>();
        int published = 0;
        for (Pending event : pending) {
            if (blocked.contains(event.claimId())) {
                continue;
            }
            try {
                dispatcher.dispatch(event.event(), event.claimId());
                jdbc.sql("UPDATE core.outbox_events SET published_at = now() WHERE id = ?").param(event.id()).update();
                published++;
            } catch (RuntimeException exception) {
                blocked.add(event.claimId());
                log.warn("Outbox event {} not delivered: {}", event.id(), exception.getClass().getSimpleName());
                jdbc.sql("UPDATE core.outbox_events SET attempts = attempts + 1, last_failure = ? WHERE id = ?")
                        .params(exception.getClass().getSimpleName(), event.id())
                        .update();
            }
        }
        return published;
    }
}
