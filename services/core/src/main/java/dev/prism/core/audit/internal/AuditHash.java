package dev.prism.core.audit.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical hashing for audit events. The serialization must never change once events exist:
 * doing so would make every stored chain fail verification.
 */
@Component
public class AuditHash {

    public static final byte[] GENESIS = new byte[32];

    private final JsonMapper jsonMapper;

    public AuditHash(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String canonicalDetails(Map<String, String> details) {
        return jsonMapper.writeValueAsString(new TreeMap<>(details));
    }

    public byte[] hash(AuditRecord record) {
        List<String> fields = List.of(
                record.stream(),
                Long.toString(record.sequence()),
                record.occurredAt().toString(),
                Objects.toString(record.actorId(), ""),
                record.actorRoles().stream().sorted().collect(Collectors.joining(",")),
                record.action(),
                Objects.toString(record.targetType(), ""),
                Objects.toString(record.targetId(), ""),
                record.outcome(),
                Objects.toString(record.correlationId(), ""),
                canonicalDetails(record.details()),
                HexFormat.of().formatHex(record.previousHash()));
        try {
            // Length prefixes stop values from shifting across field boundaries undetected.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
