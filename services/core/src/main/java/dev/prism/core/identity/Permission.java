package dev.prism.core.identity;

/** Code checks permissions, never roles. Each milestone adds the permissions it needs. */
public enum Permission {
    USERS_MANAGE,
    AUDIT_READ,
    SYSTEM_VIEW
}
