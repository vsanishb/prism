package dev.prism.core.audit.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.platform.security.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Records refused actions. Anonymous refusals are not audited, so that they cannot flood the trail. */
@Component
class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AuditRecorder recorder;
    private final JsonMapper jsonMapper;

    AuditingAccessDeniedHandler(AuditRecorder recorder, JsonMapper jsonMapper) {
        this.recorder = recorder;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        boolean csrf = exception instanceof CsrfException;
        if (CurrentActor.find().isPresent()) {
            recorder.recordIndependently(AuditEntry.of(
                            AuditEntry.IDENTITY_STREAM, AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                    .detail(AuditDetail.REQUEST_PATH, request.getMethod() + " " + request.getRequestURI())
                    .detail(AuditDetail.REASON, csrf ? "csrf" : "permission"));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                csrf ? "Your session needs refreshing. Reload and try again." : "You do not have access to this.");
        problem.setProperty("code", csrf ? "csrf_invalid" : "forbidden");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
