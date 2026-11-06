package dev.prism.core.identity.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.platform.web.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SignInService {

    private static final String GENERIC_FAILURE = "Email or password is incorrect.";

    private final AuthenticationManager authenticationManager;
    private final LoginThrottle throttle;
    private final AuditRecorder audit;
    private final UserRepository users;

    SignInService(
            AuthenticationManager authenticationManager,
            LoginThrottle throttle,
            AuditRecorder audit,
            UserRepository users) {
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
        this.audit = audit;
        this.users = users;
    }

    /** Every failure gives the same response, so that callers cannot tell which accounts exist. */
    Authentication authenticate(String email, String password, String address) {
        if (throttle.isBlocked(email, address)) {
            audit.recordIndependently(failure("throttled"));
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many attempts. Try again in a few minutes.");
        }
        Authentication result;
        try {
            result = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
        } catch (AuthenticationException exception) {
            throttle.recordFailure(email, address);
            audit.recordIndependently(failure("invalid_credentials"));
            throw invalidCredentials();
        }
        PrismUserDetails user = (PrismUserDetails) result.getPrincipal();
        if (!user.isActive()) {
            throttle.recordFailure(email, address);
            audit.recordIndependently(failure("inactive_account").target("USER", user.userId()));
            throw invalidCredentials();
        }
        throttle.clear(email);
        return result;
    }

    @Transactional
    public void recordSuccess(PrismUserDetails user) {
        users.findById(user.userId()).orElseThrow().recordSignIn(Instant.now());
        audit.record(AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.SIGN_IN, AuditOutcome.SUCCESS)
                .actor(user.userId(), user.roleNames())
                .target("USER", user.userId()));
    }

    private static AuditEntry failure(String reason) {
        return AuditEntry.of(AuditEntry.IDENTITY_STREAM, AuditAction.SIGN_IN, AuditOutcome.FAILURE)
                .detail(AuditDetail.REASON, reason);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", GENERIC_FAILURE);
    }
}
