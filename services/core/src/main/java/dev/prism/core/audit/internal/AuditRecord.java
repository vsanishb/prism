package dev.prism.core.audit.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The hashed content of a stored event. */
public record AuditRecord(
        String stream,
        long sequence,
        Instant occurredAt,
        UUID actorId,
        List<String> actorRoles,
        String action,
        String targetType,
        String targetId,
        String outcome,
        String correlationId,
        Map<String, String> details,
        byte[] previousHash) {}
