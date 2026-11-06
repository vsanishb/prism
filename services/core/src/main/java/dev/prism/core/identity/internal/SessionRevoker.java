package dev.prism.core.identity.internal;

import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Ends a user's sessions immediately, so that role or status changes apply to the next request. */
@Component
class SessionRevoker {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    void revokeAll(UUID userId) {
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
    }
}
