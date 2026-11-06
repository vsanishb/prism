package dev.prism.core.audit;

import dev.prism.core.audit.internal.AuditHash;
import dev.prism.core.audit.internal.AuditRecord;
import dev.prism.core.platform.security.ActorPrincipal;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.CorrelationFilter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The only write path to the audit trail. */
@Component
public class AuditRecorder {

    private final JdbcClient jdbc;
    private final AuditHash auditHash;

    public AuditRecorder(JdbcClient jdbc, AuditHash auditHash) {
        this.jdbc = jdbc;
        this.auditHash = auditHash;
    }

    /** Records within the caller's transaction, so the action and its event commit or fail together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        append(entry);
    }

    /** For events that must survive a rollback or have no business transaction, such as denied access. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        append(entry);
    }

    private void append(AuditEntry entry) {
        // Serializes writers per stream: the next sequence and previous hash must be read
        // and written atomically for the chain to stay linear.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(entry.stream())
                .query(resultSet -> {});

        Optional<ChainHead> head = jdbc.sql("""
                        SELECT sequence, hash FROM core.audit_events
                        WHERE stream = ? ORDER BY sequence DESC LIMIT 1
                        """)
                .param(entry.stream())
                .query((row, index) -> new ChainHead(row.getLong("sequence"), row.getBytes("hash")))
                .optional();

        UUID actorId = entry.actorId();
        Set<String> actorRoles = entry.actorRoles();
        if (actorId == null) {
            Optional<ActorPrincipal> actor = CurrentActor.find();
            actorId = actor.map(ActorPrincipal::userId).orElse(null);
            actorRoles = actor.<Set<String>>map(found -> new TreeSet<>(found.roleNames())).orElse(Set.of());
        }

        Map<String, String> details = entry.details().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue, (a, b) -> b, TreeMap::new));

        // Truncated to Postgres precision, so that the verifier hashes exactly what is stored.
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AuditRecord record = new AuditRecord(
                entry.stream(),
                head.map(ChainHead::sequence).orElse(0L) + 1,
                occurredAt,
                actorId,
                List.copyOf(new TreeSet<>(actorRoles)),
                entry.action().name(),
                entry.targetType(),
                entry.targetId(),
                entry.outcome().name(),
                MDC.get(CorrelationFilter.MDC_KEY),
                details,
                head.map(ChainHead::hash).orElse(AuditHash.GENESIS));

        jdbc.sql("""
                        INSERT INTO core.audit_events (stream, sequence, occurred_at, actor_id, actor_roles, action,
                            target_type, target_id, outcome, correlation_id, details, previous_hash, hash)
                        VALUES (?, ?, ?, ?, string_to_array(?, ','), ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(
                        record.stream(),
                        record.sequence(),
                        record.occurredAt().atOffset(ZoneOffset.UTC),
                        record.actorId(),
                        String.join(",", record.actorRoles()),
                        record.action(),
                        record.targetType(),
                        record.targetId(),
                        record.outcome(),
                        record.correlationId(),
                        auditHash.canonicalDetails(record.details()),
                        record.previousHash(),
                        auditHash.hash(record))
                .update();
    }

    private record ChainHead(long sequence, byte[] hash) {}
}
