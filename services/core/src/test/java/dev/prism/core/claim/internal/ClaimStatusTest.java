package dev.prism.core.claim.internal;

import static dev.prism.core.claim.internal.ClaimStatus.AWAITING_INFORMATION;
import static dev.prism.core.claim.internal.ClaimStatus.DECIDED;
import static dev.prism.core.claim.internal.ClaimStatus.DRAFT;
import static dev.prism.core.claim.internal.ClaimStatus.ESCALATED;
import static dev.prism.core.claim.internal.ClaimStatus.IN_REVIEW;
import static dev.prism.core.claim.internal.ClaimStatus.PROCESSING;
import static dev.prism.core.claim.internal.ClaimStatus.SUBMITTED;
import static dev.prism.core.claim.internal.ClaimStatus.WITHDRAWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaimStatusTest {

    private static final Map<ClaimStatus, Set<ClaimStatus>> EXPECTED = Map.of(
            DRAFT, EnumSet.of(SUBMITTED, WITHDRAWN),
            SUBMITTED, EnumSet.of(PROCESSING, WITHDRAWN),
            PROCESSING, EnumSet.of(AWAITING_INFORMATION, IN_REVIEW, WITHDRAWN),
            // A request for information can be withdrawn straight to an investigation or senior task (M8).
            AWAITING_INFORMATION, EnumSet.of(PROCESSING, IN_REVIEW, ESCALATED, WITHDRAWN),
            IN_REVIEW, EnumSet.of(PROCESSING, AWAITING_INFORMATION, ESCALATED, DECIDED, WITHDRAWN),
            // Escalated claims can ask for information and be processed again, like any in review (M8).
            ESCALATED, EnumSet.of(PROCESSING, AWAITING_INFORMATION, IN_REVIEW, DECIDED, WITHDRAWN),
            DECIDED, EnumSet.noneOf(ClaimStatus.class),
            WITHDRAWN, EnumSet.noneOf(ClaimStatus.class));

    /** Checks all 64 pairs, so that an unintended transition cannot slip in. */
    @Test
    void everyTransitionMatchesTheLifecycle() {
        for (ClaimStatus from : ClaimStatus.values()) {
            for (ClaimStatus to : ClaimStatus.values()) {
                assertThat(from.canBecome(to))
                        .as("%s to %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void onlyDecidedAndWithdrawnAreTerminal() {
        assertThat(EnumSet.allOf(ClaimStatus.class).stream().filter(ClaimStatus::isTerminal))
                .containsExactlyInAnyOrder(DECIDED, WITHDRAWN);
    }
}
