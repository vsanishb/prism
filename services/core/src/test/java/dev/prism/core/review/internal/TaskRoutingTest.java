package dev.prism.core.review.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.review.internal.TaskRouting.Queue;
import dev.prism.core.review.internal.TaskRouting.Route;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every route, and the amount rules for each decision. */
class TaskRoutingTest {

    private static final BigDecimal LIMIT = new BigDecimal("300000");
    private static final BigDecimal SMALL = new BigDecimal("98400");
    private static final BigDecimal LARGE = new BigDecimal("392000");

    @Test
    void readyClaimsAreRoutedByRiskThenAmount() {
        assertThat(TaskRouting.ready("LOW", false, SMALL, LIMIT, null)).isEqualTo(new Route(Queue.REVIEW, "STANDARD"));
        assertThat(TaskRouting.ready("MEDIUM", false, SMALL, LIMIT, null)).isEqualTo(new Route(Queue.REVIEW, "STANDARD"));
        assertThat(TaskRouting.ready(null, false, SMALL, LIMIT, null)).isEqualTo(new Route(Queue.REVIEW, "STANDARD"));
        assertThat(TaskRouting.ready("LOW", false, LARGE, LIMIT, null)).isEqualTo(new Route(Queue.SENIOR_REVIEW, "ABOVE_AUTHORITY_LIMIT"));
        assertThat(TaskRouting.ready("LOW", false, LIMIT, LIMIT, null).queue()).isEqualTo(Queue.REVIEW);
        assertThat(TaskRouting.ready("HIGH", false, SMALL, LIMIT, null)).isEqualTo(new Route(Queue.INVESTIGATION, "HIGH_RISK"));
        assertThat(TaskRouting.ready("HIGH", false, LARGE, LIMIT, null)).isEqualTo(new Route(Queue.INVESTIGATION, "HIGH_RISK"));
    }

    @Test
    void anInvestigatedClaimIsNotSentBackToInvestigationByItself() {
        assertThat(TaskRouting.ready("HIGH", true, SMALL, LIMIT, "INFORMATION_RECEIVED"))
                .isEqualTo(new Route(Queue.REVIEW, "INFORMATION_RECEIVED"));
    }

    @Test
    void afterAnInvestigationConfirmedFraudAndLargeClaimsGoToSeniors() {
        assertThat(TaskRouting.afterInvestigation("NO_CONCERN", SMALL, LIMIT)).isEqualTo(new Route(Queue.REVIEW, "INVESTIGATED"));
        assertThat(TaskRouting.afterInvestigation("INCONCLUSIVE", LARGE, LIMIT).queue()).isEqualTo(Queue.SENIOR_REVIEW);
        assertThat(TaskRouting.afterInvestigation("CONFIRMED_FRAUD", SMALL, LIMIT).queue()).isEqualTo(Queue.SENIOR_REVIEW);
    }

    @Test
    void escalationOnlyGoesUp() {
        assertThat(TaskRouting.canEscalate(Queue.REVIEW, Queue.SENIOR_REVIEW)).isTrue();
        assertThat(TaskRouting.canEscalate(Queue.REVIEW, Queue.INVESTIGATION)).isTrue();
        assertThat(TaskRouting.canEscalate(Queue.SENIOR_REVIEW, Queue.INVESTIGATION)).isTrue();
        assertThat(TaskRouting.canEscalate(Queue.SENIOR_REVIEW, Queue.REVIEW)).isFalse();
        assertThat(TaskRouting.canEscalate(Queue.REVIEW, Queue.REVIEW)).isFalse();
        assertThat(TaskRouting.canEscalate(Queue.INVESTIGATION, Queue.SENIOR_REVIEW)).isFalse();
    }

    @Test
    void investigationsAndEscalationsMarkTheClaimEscalated() {
        assertThat(TaskRouting.escalates(new Route(Queue.INVESTIGATION, "HIGH_RISK"))).isTrue();
        assertThat(TaskRouting.escalates(new Route(Queue.SENIOR_REVIEW, "ESCALATED"))).isTrue();
        assertThat(TaskRouting.escalates(new Route(Queue.SENIOR_REVIEW, "ABOVE_AUTHORITY_LIMIT"))).isFalse();
    }

    private static ClaimRecord claim() {
        return new ClaimRecord(UUID.randomUUID(), "REIMBURSEMENT", "IN_REVIEW", false, "C", "P", "H", LocalDate.parse("2026-03-14"),
                LocalDate.parse("2026-03-17"), new BigDecimal("106000.00"), "INR", UUID.randomUUID(), UUID.randomUUID(),
                "CLM-2026-000001", UUID.randomUUID());
    }

    @Test
    void anApprovalPaysTheClaimedAmountAndAPartialApprovalLess() {
        assertThat(ReviewActionService.approved("APPROVED", null, claim())).isEqualByComparingTo("106000.00");
        assertThat(ReviewActionService.approved("APPROVED", new BigDecimal("106000"), claim())).isEqualByComparingTo("106000.00");
        assertThat(ReviewActionService.approved("PARTIALLY_APPROVED", new BigDecimal("90000.5"), claim())).isEqualByComparingTo("90000.50");
        assertThat(ReviewActionService.approved("DENIED", null, claim())).isNull();
        for (Object[] invalid : new Object[][] {
                {"APPROVED", new BigDecimal("1")}, {"PARTIALLY_APPROVED", null}, {"PARTIALLY_APPROVED", new BigDecimal("0")},
                {"PARTIALLY_APPROVED", new BigDecimal("106000")}, {"PARTIALLY_APPROVED", new BigDecimal("1.001")},
                {"DENIED", new BigDecimal("1")}, {"PENDING", null}}) {
            assertThatThrownBy(() -> ReviewActionService.approved((String) invalid[0], (BigDecimal) invalid[1], claim()))
                    .isInstanceOf(ApiException.class);
        }
    }
}
