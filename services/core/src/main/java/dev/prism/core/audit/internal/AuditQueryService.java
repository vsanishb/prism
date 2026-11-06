package dev.prism.core.audit.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.platform.web.Page;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
class AuditQueryService {

    private static final TypeReference<TreeMap<String, String>> DETAILS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final AuditHash auditHash;
    private final AuditRecorder recorder;

    AuditQueryService(JdbcClient jdbc, JsonMapper jsonMapper, AuditHash auditHash, AuditRecorder recorder) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.auditHash = auditHash;
        this.recorder = recorder;
    }

    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Transactional(readOnly = true)
    public Page<AuditEventView> search(AuditFilter filter, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.*, u.full_name AS actor_name
                FROM core.audit_events e LEFT JOIN core.users u ON u.id = e.actor_id
                WHERE TRUE
                """);
        List<Object> params = new ArrayList<>();
        if (filter.action() != null) {
            sql.append(" AND e.action = ?");
            params.add(filter.action().name());
        }
        if (filter.outcome() != null) {
            sql.append(" AND e.outcome = ?");
            params.add(filter.outcome().name());
        }
        if (filter.actorId() != null) {
            sql.append(" AND e.actor_id = ?");
            params.add(filter.actorId());
        }
        if (cursor != null) {
            Cursor position = Cursor.decode(cursor);
            sql.append(" AND (e.occurred_at, e.id) < (?, ?)");
            params.add(position.occurredAt().atOffset(ZoneOffset.UTC));
            params.add(position.id());
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?");
        params.add(limit + 1);

        List<AuditEventView> rows = jdbc.sql(sql.toString()).params(params).query(this::toView).list();
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<AuditEventView> items = rows.subList(0, limit);
        AuditEventView last = items.getLast();
        return new Page<>(items, new Cursor(last.occurredAt(), last.id()).encode());
    }

    /** Recomputes every chain, stopping at the first event that does not match. */
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public ChainVerification verify() {
        List<String> streams = jdbc.sql("SELECT DISTINCT stream FROM core.audit_events ORDER BY stream")
                .query(String.class)
                .list();
        ChainWalk walk = new ChainWalk();
        for (String stream : streams) {
            walk.startStream();
            jdbc.sql("SELECT * FROM core.audit_events WHERE stream = ? ORDER BY sequence")
                    .param(stream)
                    .query((RowCallbackHandler) row -> walk.check(toRecord(row), row.getBytes("hash")));
            if (walk.firstBreak != null) {
                break;
            }
        }
        ChainVerification result = new ChainVerification(walk.firstBreak == null, streams.size(), walk.events, walk.firstBreak);
        recorder.recordIndependently(AuditEntry.of(
                        AuditEntry.IDENTITY_STREAM, AuditAction.AUDIT_CHAIN_VERIFIED, AuditOutcome.SUCCESS)
                .detail(AuditDetail.STREAMS_CHECKED, result.streamsChecked())
                .detail(AuditDetail.EVENTS_CHECKED, result.eventsChecked())
                .detail(AuditDetail.CHAIN_VALID, result.valid()));
        return result;
    }

    private AuditRecord toRecord(ResultSet row) throws SQLException {
        return new AuditRecord(
                row.getString("stream"),
                row.getLong("sequence"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("actor_id", UUID.class),
                Arrays.asList((String[]) row.getArray("actor_roles").getArray()),
                row.getString("action"),
                row.getString("target_type"),
                row.getString("target_id"),
                row.getString("outcome"),
                row.getString("correlation_id"),
                readDetails(row),
                row.getBytes("previous_hash"));
    }

    private AuditEventView toView(ResultSet row, int index) throws SQLException {
        return new AuditEventView(
                row.getObject("id", UUID.class),
                row.getString("stream"),
                row.getLong("sequence"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("actor_id", UUID.class),
                row.getString("actor_name"),
                Arrays.asList((String[]) row.getArray("actor_roles").getArray()),
                row.getString("action"),
                row.getString("target_type"),
                row.getString("target_id"),
                row.getString("outcome"),
                row.getString("correlation_id"),
                readDetails(row));
    }

    private Map<String, String> readDetails(ResultSet row) throws SQLException {
        return jsonMapper.readValue(row.getString("details"), DETAILS_TYPE);
    }

    private final class ChainWalk {
        private byte[] expectedPrevious;
        private long expectedSequence;
        private long events;
        private ChainBreak firstBreak;

        void startStream() {
            expectedPrevious = AuditHash.GENESIS;
            expectedSequence = 1;
        }

        void check(AuditRecord record, byte[] storedHash) {
            if (firstBreak != null) {
                return;
            }
            events++;
            boolean linked = record.sequence() == expectedSequence
                    && Arrays.equals(record.previousHash(), expectedPrevious);
            if (!linked || !Arrays.equals(auditHash.hash(record), storedHash)) {
                firstBreak = new ChainBreak(record.stream(), record.sequence());
                return;
            }
            expectedPrevious = storedHash;
            expectedSequence++;
        }
    }

    private record Cursor(Instant occurredAt, UUID id) {

        String encode() {
            String raw = occurredAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                return new Cursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
            } catch (RuntimeException exception) {
                throw ApiException.invalid("invalid_cursor", "The page cursor is not valid.");
            }
        }
    }

    record AuditFilter(AuditAction action, AuditOutcome outcome, UUID actorId) {}

    record AuditEventView(
            UUID id,
            String stream,
            long sequence,
            Instant occurredAt,
            UUID actorId,
            String actorName,
            List<String> actorRoles,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String correlationId,
            Map<String, String> details) {}

    record ChainBreak(String stream, long sequence) {}

    record ChainVerification(boolean valid, int streamsChecked, long eventsChecked, ChainBreak firstBreak) {}
}
