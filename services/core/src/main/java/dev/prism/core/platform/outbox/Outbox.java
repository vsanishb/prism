package dev.prism.core.platform.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces a committed change to the workflow engine. Lives in platform so that feature modules can
 * publish without depending on the workflow module that consumes these events.
 */
@Component
public class Outbox {

    public enum Event {
        CLAIM_SUBMITTED,
        CLAIM_DOCUMENTS_CHANGED,
        CLAIM_RETRY_REQUESTED,
        CLAIM_WITHDRAWN
    }

    private final JdbcClient jdbc;

    public Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Commits with the caller's change, so that neither can happen without the other. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Event event, UUID aggregateId) {
        jdbc.sql("INSERT INTO core.outbox_events (event_type, aggregate_id) VALUES (?, ?)")
                .params(event.name(), aggregateId)
                .update();
    }
}
