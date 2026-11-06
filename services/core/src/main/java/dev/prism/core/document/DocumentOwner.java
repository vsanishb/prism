package dev.prism.core.document;

import java.util.UUID;

/** What a document belongs to, and the audit stream its events join. */
public record DocumentOwner(Type type, UUID id, String auditStream) {

    public enum Type {
        CLAIM,
        POLICY_VERSION
    }
}
