package dev.prism.core.identity.internal;

import dev.prism.core.identity.Role;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates users directly, so that tests can focus on the behavior under test. */
@Component
public class IdentityFixtures {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    IdentityFixtures(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public UUID createUser(String email, Role role, String password) {
        return users.save(new UserEntity(email, "Pooja Rawat", encoder.encode(password), Set.of(role))).id();
    }

    @Transactional
    public void deactivateDirectly(UUID userId) {
        users.findById(userId).orElseThrow().changeStatus(UserEntity.Status.DEACTIVATED);
    }
}
