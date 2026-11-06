package dev.prism.core.audit;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * An event to record. The actor defaults to the signed-in user; sign-in supplies it explicitly
 * because no security context exists yet.
 */
public final class AuditEntry {

    public static final String IDENTITY_STREAM = "identity";

    private final String stream;
    private final AuditAction action;
    private final AuditOutcome outcome;
    private final Map<AuditDetail, String> details = new EnumMap<>(AuditDetail.class);
    private String targetType;
    private String targetId;
    private UUID actorId;
    private Set<String> actorRoles;

    private AuditEntry(String stream, AuditAction action, AuditOutcome outcome) {
        this.stream = stream;
        this.action = action;
        this.outcome = outcome;
    }

    public static AuditEntry of(String stream, AuditAction action, AuditOutcome outcome) {
        return new AuditEntry(stream, action, outcome);
    }

    public AuditEntry target(String type, Object id) {
        this.targetType = type;
        this.targetId = id == null ? null : id.toString();
        return this;
    }

    public AuditEntry actor(UUID id, Collection<String> roles) {
        this.actorId = id;
        this.actorRoles = new TreeSet<>(roles);
        return this;
    }

    public AuditEntry detail(AuditDetail key, Object value) {
        details.put(key, String.valueOf(value));
        return this;
    }

    public String stream() {
        return stream;
    }

    public AuditAction action() {
        return action;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public String targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public UUID actorId() {
        return actorId;
    }

    public Set<String> actorRoles() {
        return actorRoles;
    }

    public Map<AuditDetail, String> details() {
        return details;
    }
}
