package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users", schema = "core")
class UserEntity {

    enum Status {
        ACTIVE,
        DEACTIVATED
    }

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", schema = "core", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Version
    private long version;

    protected UserEntity() {}

    UserEntity(String email, String fullName, String passwordHash, Set<Role> roles) {
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.roles = new HashSet<>(roles);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String fullName() {
        return fullName;
    }

    String passwordHash() {
        return passwordHash;
    }

    Status status() {
        return status;
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    Set<Role> roles() {
        return roles.isEmpty() ? Set.of() : EnumSet.copyOf(roles);
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastSignInAt() {
        return lastSignInAt;
    }

    void changePassword(String newHash) {
        this.passwordHash = newHash;
    }

    void changeRoles(Set<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    void changeStatus(Status newStatus) {
        this.status = newStatus;
    }

    void recordSignIn(Instant at) {
        this.lastSignInAt = at;
    }
}
