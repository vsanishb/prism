package dev.prism.core.identity.internal;

import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrismUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserRepository users;

    PrismUserDetailsService(UserRepository users) {
        this.users = users;
    }

    /**
     * Inactive accounts load normally and are refused after the password check, so that their
     * response time matches every other failed attempt.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(PrismUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }

    /** Called by Spring Security to re-hash a password when encoder settings have been strengthened. */
    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        UserEntity entity = users.findById(UUID.fromString(user.getUsername())).orElseThrow();
        entity.changePassword(newPassword);
        return new PrismUserDetails(entity);
    }
}
