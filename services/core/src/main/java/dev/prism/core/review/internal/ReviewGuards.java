package dev.prism.core.review.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.platform.web.ApiException;
import java.util.UUID;

/** Separation of duties (security.md). Refusals are audited in their own transaction, so they survive the rollback. */
final class ReviewGuards {

    private ReviewGuards() {}

    static void requireNotCreator(ClaimRecord claim, UUID user, AuditRecorder audit) {
        if (user.equals(claim.createdBy())) {
            deny(claim.id(), "created_claim", audit);
            throw ApiException.forbidden("separation_of_duties", "You created this claim, so someone else must review and decide it.");
        }
    }

    static void deny(UUID claimId, String reason, AuditRecorder audit) {
        audit.recordIndependently(AuditEntry.of("claim:" + claimId, AuditAction.ACCESS_DENIED, AuditOutcome.DENIED)
                .target("CLAIM", claimId)
                .detail(AuditDetail.REASON, reason));
    }
}
