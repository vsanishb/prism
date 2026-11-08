package dev.prism.core.claim;

import java.util.UUID;

/**
 * Status changes driven by human review. Each joins the caller's transaction, so a status change and
 * the review record that causes it commit together, and each is audited. A change that is already
 * true does nothing.
 */
public interface ClaimReview {

    /** Back to the review queues: after an investigation, or when a request for information is withdrawn. */
    void returnToReview(UUID claimId);

    /** With a senior reviewer or an investigator. */
    void escalate(UUID claimId);

    /** Waiting for documents a reviewer asked for. New documents restart processing. */
    void awaitRequestedInformation(UUID claimId);

    /** An authorized outcome was recorded. Terminal. */
    void decide(UUID claimId);
}
