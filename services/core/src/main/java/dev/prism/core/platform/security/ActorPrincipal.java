package dev.prism.core.platform.security;

import java.util.Set;
import java.util.UUID;

/** What the platform needs to know about a signed-in user, without depending on the identity module. */
public interface ActorPrincipal {

    UUID userId();

    Set<String> roleNames();
}
