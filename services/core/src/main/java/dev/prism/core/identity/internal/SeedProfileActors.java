package dev.prism.core.identity.internal;

import dev.prism.core.identity.SyntheticActors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/** Exists only in the seed profile: no running application can impersonate a user this way. */
@Component
@Profile("seed")
class SeedProfileActors implements SyntheticActors {

    private final PrismUserDetailsService users;

    SeedProfileActors(PrismUserDetailsService users) {
        this.users = users;
    }

    @Override
    public void runAs(String email, Runnable action) {
        UserDetails user = users.loadUserByUsername(email);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
