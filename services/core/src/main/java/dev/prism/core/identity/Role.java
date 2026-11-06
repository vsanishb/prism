package dev.prism.core.identity;

import java.util.Set;

public enum Role {
    ADMIN(Set.of(Permission.USERS_MANAGE, Permission.SYSTEM_VIEW)),
    POLICY_MANAGER(Set.of()),
    CLAIMS_PROCESSOR(Set.of()),
    REVIEWER(Set.of()),
    SENIOR_REVIEWER(Set.of()),
    FRAUD_INVESTIGATOR(Set.of()),
    AUDITOR(Set.of(Permission.AUDIT_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
