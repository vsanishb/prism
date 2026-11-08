package dev.prism.core.claim.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimReview;
import dev.prism.core.platform.web.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Authorization is the review module's: it checks who may act before calling here. */
@Service
class ClaimReviewService implements ClaimReview {

    private final ClaimRepository claims;
    private final AuditRecorder audit;

    ClaimReviewService(ClaimRepository claims, AuditRecorder audit) {
        this.claims = claims;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnToReview(UUID claimId) {
        move(claimId, ClaimStatus.IN_REVIEW, AuditAction.CLAIM_RETURNED_TO_REVIEW);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void escalate(UUID claimId) {
        move(claimId, ClaimStatus.ESCALATED, AuditAction.CLAIM_ESCALATED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void awaitRequestedInformation(UUID claimId) {
        move(claimId, ClaimStatus.AWAITING_INFORMATION, AuditAction.CLAIM_AWAITING_INFORMATION);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void decide(UUID claimId) {
        move(claimId, ClaimStatus.DECIDED, AuditAction.CLAIM_DECIDED);
    }

    private void move(UUID claimId, ClaimStatus next, AuditAction action) {
        ClaimEntity claim = claims.lockById(claimId).orElseThrow(() -> ApiException.notFound("Claim not found."));
        ClaimStatus before = claim.status();
        if (before == next) {
            return;
        }
        if (!before.canBecome(next)) {
            throw ApiException.conflict("invalid_transition", "This action is not available for a claim in its current state.");
        }
        claim.advanceTo(next);
        audit.record(AuditEntry.of("claim:" + claimId, action, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId)
                .detail(AuditDetail.STATUS_BEFORE, before)
                .detail(AuditDetail.STATUS_AFTER, next));
    }
}
