package dev.prism.core.audit.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.internal.AuditQueryService.AuditEventView;
import dev.prism.core.audit.internal.AuditQueryService.AuditFilter;
import dev.prism.core.audit.internal.AuditQueryService.ChainVerification;
import dev.prism.core.platform.web.Page;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditQueryService service;

    AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/events")
    Page<AuditEventView> events(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        return service.search(new AuditFilter(action, outcome, actorId), cursor, size);
    }

    @PostMapping("/verification")
    ChainVerification verify() {
        return service.verify();
    }
}
