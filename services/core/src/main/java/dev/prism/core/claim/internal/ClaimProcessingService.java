package dev.prism.core.claim.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimProcessing;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs as the system: these calls come from workflow activities, which have no signed-in user. */
@Service
class ClaimProcessingService implements ClaimProcessing {

    private static final Set<ClaimStatus> CAN_BEGIN =
            Set.of(ClaimStatus.SUBMITTED, ClaimStatus.AWAITING_INFORMATION, ClaimStatus.IN_REVIEW, ClaimStatus.ESCALATED);

    private final ClaimRepository claims;
    private final AuditRecorder audit;

    ClaimProcessingService(ClaimRepository claims, AuditRecorder audit) {
        this.claims = claims;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void begin(UUID claimId) {
        ClaimEntity claim = locked(claimId);
        if (CAN_BEGIN.contains(claim.status())) {
            move(claim, ClaimStatus.PROCESSING, AuditAction.CLAIM_PROCESSING_STARTED);
        }
    }

    @Override
    @Transactional
    public void awaitInformation(UUID claimId) {
        ClaimEntity claim = locked(claimId);
        if (claim.status() == ClaimStatus.PROCESSING) {
            move(claim, ClaimStatus.AWAITING_INFORMATION, AuditAction.CLAIM_AWAITING_INFORMATION);
        }
    }

    @Override
    @Transactional
    public void readyForReview(UUID claimId) {
        ClaimEntity claim = locked(claimId);
        if (claim.status() == ClaimStatus.PROCESSING || claim.status() == ClaimStatus.AWAITING_INFORMATION) {
            move(claim, ClaimStatus.IN_REVIEW, AuditAction.CLAIM_READY_FOR_REVIEW);
        }
    }

    private ClaimEntity locked(UUID claimId) {
        return claims.lockById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown claim " + claimId));
    }

    private void move(ClaimEntity claim, ClaimStatus next, AuditAction action) {
        ClaimStatus before = claim.status();
        claim.advanceTo(next);
        audit.record(AuditEntry.of("claim:" + claim.id(), action, AuditOutcome.SUCCESS)
                .target("CLAIM", claim.id())
                .detail(AuditDetail.STATUS_BEFORE, before)
                .detail(AuditDetail.STATUS_AFTER, next));
    }
}
