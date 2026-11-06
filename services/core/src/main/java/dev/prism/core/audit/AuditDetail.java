package dev.prism.core.audit;

/**
 * The allow-list of keys that may appear in event details. A closed set keeps personal and health
 * data from reaching the audit trail through free-form fields.
 */
public enum AuditDetail {
    REASON,
    ROLES_BEFORE,
    ROLES_AFTER,
    REQUEST_PATH,
    STREAMS_CHECKED,
    EVENTS_CHECKED,
    CHAIN_VALID
}
