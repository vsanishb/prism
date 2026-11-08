package dev.prism.core.review.internal;

import dev.prism.core.review.internal.TaskRouting.Queue;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Review tasks. A claim has at most one live task, which the database enforces. State changes are
 * conditional updates, so two people taking or finishing the same task cannot both succeed.
 */
@Repository
class ReviewTaskStore {

    private final JdbcClient jdbc;

    ReviewTaskStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Task(
            UUID id,
            UUID claimId,
            Queue queue,
            String reason,
            String status,
            UUID assigneeId,
            String assigneeName,
            String completion,
            String escalationNote,
            Instant createdAt,
            Instant claimedAt,
            Instant completedAt) {}

    private static final String SELECT = """
            SELECT t.*, u.full_name AS assignee_name FROM core.review_tasks t
            LEFT JOIN core.users u ON u.id = t.assignee_id
            """;

    private static final RowMapper<Task> TASK = (row, index) -> new Task(
            row.getObject("id", UUID.class),
            row.getObject("claim_id", UUID.class),
            Queue.valueOf(row.getString("queue")),
            row.getString("reason"),
            row.getString("status"),
            row.getObject("assignee_id", UUID.class),
            row.getString("assignee_name"),
            row.getString("completion"),
            row.getString("escalation_note"),
            instant(row.getObject("created_at", OffsetDateTime.class)),
            instant(row.getObject("claimed_at", OffsetDateTime.class)),
            instant(row.getObject("completed_at", OffsetDateTime.class)));

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    UUID open(UUID claimId, Queue queue, String reason, String escalationNote) {
        return jdbc.sql("""
                        INSERT INTO core.review_tasks (claim_id, queue, reason, escalation_note)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """)
                .params(claimId, queue.name(), reason, escalationNote)
                .query(UUID.class)
                .single();
    }

    Optional<Task> find(UUID taskId) {
        return jdbc.sql(SELECT + " WHERE t.id = ?").param(taskId).query(TASK).optional();
    }

    /** The claim's live task, locked for the rest of the transaction. */
    Optional<Task> lockLive(UUID claimId) {
        return jdbc.sql("SELECT id FROM core.review_tasks WHERE claim_id = ? AND status IN ('OPEN', 'CLAIMED') FOR UPDATE")
                .param(claimId)
                .query(UUID.class)
                .optional()
                .flatMap(this::find);
    }

    Optional<Task> live(UUID claimId) {
        return jdbc.sql(SELECT + " WHERE t.claim_id = ? AND t.status IN ('OPEN', 'CLAIMED')").param(claimId).query(TASK).optional();
    }

    List<Task> history(UUID claimId) {
        return jdbc.sql(SELECT + " WHERE t.claim_id = ? ORDER BY t.id").param(claimId).query(TASK).list();
    }

    Optional<Task> lastCompleted(UUID claimId) {
        return jdbc.sql(SELECT + " WHERE t.claim_id = ? AND t.status IN ('COMPLETED', 'CANCELLED') ORDER BY t.id DESC LIMIT 1")
                .param(claimId)
                .query(TASK)
                .optional();
    }

    boolean claim(UUID taskId, UUID assignee) {
        return jdbc.sql("""
                        UPDATE core.review_tasks SET status = 'CLAIMED', assignee_id = ?, claimed_at = now(), version = version + 1
                        WHERE id = ? AND status = 'OPEN'
                        """)
                .params(assignee, taskId)
                .update() == 1;
    }

    boolean release(UUID taskId, UUID assignee) {
        return jdbc.sql("""
                        UPDATE core.review_tasks SET status = 'OPEN', assignee_id = NULL, claimed_at = NULL, version = version + 1
                        WHERE id = ? AND status = 'CLAIMED' AND assignee_id = ?
                        """)
                .params(taskId, assignee)
                .update() == 1;
    }

    boolean complete(UUID taskId, UUID assignee, String completion) {
        return jdbc.sql("""
                        UPDATE core.review_tasks SET status = 'COMPLETED', completion = ?, completed_at = now(), version = version + 1
                        WHERE id = ? AND status = 'CLAIMED' AND assignee_id = ?
                        """)
                .params(completion, taskId, assignee)
                .update() == 1;
    }

    boolean cancel(UUID taskId) {
        return jdbc.sql("""
                        UPDATE core.review_tasks SET status = 'CANCELLED', assignee_id = NULL, completion = 'REPROCESSING',
                            completed_at = now(), version = version + 1
                        WHERE id = ? AND status IN ('OPEN', 'CLAIMED')
                        """)
                .param(taskId)
                .update() == 1;
    }

    /** Open tasks in these queues, oldest first, on claims still open. */
    List<Task> available(Collection<Queue> queues, int limit) {
        if (queues.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + """
                         JOIN core.claims c ON c.id = t.claim_id
                         WHERE t.status = 'OPEN' AND t.queue = ANY (?::text[]) AND c.status NOT IN ('DECIDED', 'WITHDRAWN')
                         ORDER BY t.created_at, t.id LIMIT ?
                        """)
                .params(array(queues), limit)
                .query(TASK)
                .list();
    }

    List<Task> claimedBy(UUID assignee) {
        return jdbc.sql(SELECT + """
                         JOIN core.claims c ON c.id = t.claim_id
                         WHERE t.status = 'CLAIMED' AND t.assignee_id = ? AND c.status NOT IN ('DECIDED', 'WITHDRAWN')
                         ORDER BY t.claimed_at
                        """)
                .param(assignee)
                .query(TASK)
                .list();
    }

    /**
     * The oldest open task this user may take, locked so that two people asking at once get different
     * tasks. Claims the user created are skipped: they may not review them.
     */
    Optional<UUID> lockNext(Collection<Queue> queues, UUID user) {
        if (queues.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT t.id FROM core.review_tasks t JOIN core.claims c ON c.id = t.claim_id
                        WHERE t.status = 'OPEN' AND t.queue = ANY (?::text[]) AND c.status NOT IN ('DECIDED', 'WITHDRAWN')
                            AND c.created_by <> ?
                        ORDER BY t.created_at, t.id LIMIT 1
                        FOR UPDATE OF t SKIP LOCKED
                        """)
                .params(array(queues), user)
                .query(UUID.class)
                .optional();
    }

    record QueueCount(Queue queue, int open) {}

    List<QueueCount> counts(Collection<Queue> queues) {
        if (queues.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT t.queue, count(*) AS open FROM core.review_tasks t JOIN core.claims c ON c.id = t.claim_id
                        WHERE t.status = 'OPEN' AND t.queue = ANY (?::text[]) AND c.status NOT IN ('DECIDED', 'WITHDRAWN')
                        GROUP BY t.queue
                        """)
                .param(array(queues))
                .query((row, index) -> new QueueCount(Queue.valueOf(row.getString("queue")), row.getInt("open")))
                .list();
    }

    /** Queue names are enum constants, so this literal is safe. */
    private static String array(Collection<Queue> queues) {
        return "{" + String.join(",", queues.stream().map(Queue::name).toList()) + "}";
    }
}
