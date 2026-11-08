package dev.prism.core.review;

import java.util.UUID;

/** Review tasks as the claim workflow sees them. Both calls are idempotent, because activities can repeat. */
public interface ReviewQueue {

    /** Opens the claim's review task, routed by risk and amount, unless one is already live. */
    void openTask(UUID claimId);

    /** Cancels a live task because the claim is being processed again. A new one opens when it is ready. */
    void cancelLiveTask(UUID claimId);
}
