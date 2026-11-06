package dev.prism.core.platform.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentActor {

    private CurrentActor() {}

    public static Optional<ActorPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActorPrincipal actor) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }

    public static ActorPrincipal require() {
        return find().orElseThrow(() -> new IllegalStateException("No signed-in user in this context"));
    }
}
