package dev.prism.core.claim.internal;

import java.util.Map;
import java.util.Set;

/** The claim lifecycle. All transitions are declared here, and nowhere else. */
enum ClaimStatus {
    DRAFT,
    SUBMITTED,
    PROCESSING,
    AWAITING_INFORMATION,
    IN_REVIEW,
    ESCALATED,
    DECIDED,
    WITHDRAWN;

    private static final Map<ClaimStatus, Set<ClaimStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(SUBMITTED, WITHDRAWN),
            SUBMITTED, Set.of(PROCESSING, WITHDRAWN),
            PROCESSING, Set.of(AWAITING_INFORMATION, IN_REVIEW, WITHDRAWN),
            AWAITING_INFORMATION, Set.of(PROCESSING, WITHDRAWN),
            IN_REVIEW, Set.of(AWAITING_INFORMATION, ESCALATED, DECIDED, WITHDRAWN),
            ESCALATED, Set.of(IN_REVIEW, DECIDED, WITHDRAWN),
            DECIDED, Set.of(),
            WITHDRAWN, Set.of());

    boolean canBecome(ClaimStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
