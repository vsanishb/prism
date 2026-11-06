package dev.prism.core.identity;

import java.util.Set;

public enum Role {
    ADMIN(Set.of(Permission.USERS_MANAGE, Permission.SYSTEM_VIEW)),
    POLICY_MANAGER(Set.of(Permission.POLICY_READ, Permission.POLICY_MANAGE)),
    CLAIMS_PROCESSOR(Set.of(Permission.POLICY_READ, Permission.CLAIM_READ, Permission.CLAIM_MANAGE)),
    REVIEWER(Set.of(Permission.POLICY_READ, Permission.CLAIM_READ)),
    SENIOR_REVIEWER(Set.of(Permission.POLICY_READ, Permission.CLAIM_READ)),
    FRAUD_INVESTIGATOR(Set.of(Permission.POLICY_READ, Permission.CLAIM_READ)),
    AUDITOR(Set.of(Permission.AUDIT_READ, Permission.POLICY_READ, Permission.CLAIM_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
