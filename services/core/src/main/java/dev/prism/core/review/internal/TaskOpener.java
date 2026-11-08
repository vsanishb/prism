package dev.prism.core.review.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimReview;
import dev.prism.core.review.internal.TaskRouting.Route;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Opens a routed task and sets the claim's status to match, in the caller's transaction. */
@Component
class TaskOpener {

    private final ReviewTaskStore tasks;
    private final ClaimReview claims;
    private final AuditRecorder audit;

    TaskOpener(ReviewTaskStore tasks, ClaimReview claims, AuditRecorder audit) {
        this.tasks = tasks;
        this.claims = claims;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    UUID open(UUID claimId, Route route, String escalationNote) {
        UUID task = tasks.open(claimId, route.queue(), route.reason(), escalationNote);
        if (TaskRouting.escalates(route)) {
            claims.escalate(claimId);
        } else {
            claims.returnToReview(claimId);
        }
        audit.record(AuditEntry.of("claim:" + claimId, AuditAction.REVIEW_TASK_OPENED, AuditOutcome.SUCCESS)
                .target("REVIEW_TASK", task)
                .detail(AuditDetail.QUEUE, route.queue())
                .detail(AuditDetail.REASON, route.reason()));
        return task;
    }
}
