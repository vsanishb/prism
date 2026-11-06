package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import dev.prism.core.platform.security.ActorPrincipal;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The session principal. getUsername returns the user ID, because Spring Session indexes sessions
 * by this value and session revocation looks them up by ID.
 */
final class PrismUserDetails implements UserDetails, CredentialsContainer, ActorPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final boolean active;
    private final TreeSet<String> roleNames;
    private String passwordHash;

    PrismUserDetails(UserEntity user) {
        this.userId = user.id();
        this.active = user.isActive();
        this.passwordHash = user.passwordHash();
        this.roleNames = user.roles().stream().map(Role::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public Set<String> roleNames() {
        return roleNames;
    }

    boolean isActive() {
        return active;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = roleNames.stream()
                .flatMap(role -> Role.valueOf(role).permissions().stream())
                .distinct()
                .<GrantedAuthority>map(permission -> new SimpleGrantedAuthority(permission.name()))
                .toList();
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return userId.toString();
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
