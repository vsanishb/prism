package dev.prism.core.review.internal;

import java.math.BigDecimal;

/**
 * Which queue a task goes to (plan: human review). Pure, so every route is tested directly. The risk
 * band only sets who looks first: a high band never changes what can be decided.
 */
final class TaskRouting {

    enum Queue {
        REVIEW,
        SENIOR_REVIEW,
        INVESTIGATION
    }

    record Route(Queue queue, String reason) {}

    private TaskRouting() {}

    /**
     * When a claim is ready for review. A high-risk claim goes to an investigator first, once: after a
     * finding it goes to reviewers, even if a later screening is high again, unless a reviewer escalates.
     *
     * @param context why the task opens, when it is not a first review, such as INFORMATION_RECEIVED
     */
    static Route ready(String riskBand, boolean investigated, BigDecimal claimed, BigDecimal authorityLimit, String context) {
        if ("HIGH".equals(riskBand) && !investigated) {
            return new Route(Queue.INVESTIGATION, "HIGH_RISK");
        }
        if (claimed.compareTo(authorityLimit) > 0) {
            return new Route(Queue.SENIOR_REVIEW, context != null ? context : "ABOVE_AUTHORITY_LIMIT");
        }
        return new Route(Queue.REVIEW, context != null ? context : "STANDARD");
    }

    /** After an investigator's finding. Confirmed fraud is decided by a senior reviewer. */
    static Route afterInvestigation(String finding, BigDecimal claimed, BigDecimal authorityLimit) {
        boolean senior = "CONFIRMED_FRAUD".equals(finding) || claimed.compareTo(authorityLimit) > 0;
        return new Route(senior ? Queue.SENIOR_REVIEW : Queue.REVIEW, "INVESTIGATED");
    }

    /** A reviewer escalates up, never sideways or down. */
    static boolean canEscalate(Queue from, Queue to) {
        return switch (from) {
            case REVIEW -> to == Queue.SENIOR_REVIEW || to == Queue.INVESTIGATION;
            case SENIOR_REVIEW -> to == Queue.INVESTIGATION;
            case INVESTIGATION -> false;
        };
    }

    /** With a senior reviewer or an investigator, in the claim's own status. */
    static boolean escalates(Route route) {
        return route.queue() == Queue.INVESTIGATION || "ESCALATED".equals(route.reason());
    }
}
