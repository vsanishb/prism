package dev.prism.core.claim;

import java.util.UUID;

/**
 * Status changes driven by the claim workflow. Each is idempotent, because activities can repeat,
 * and does nothing once a claim is closed, because a withdrawal can overtake a running stage.
 */
public interface ClaimProcessing {

    void begin(UUID claimId);

    void awaitInformation(UUID claimId);

    void readyForReview(UUID claimId);
}
