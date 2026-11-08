package dev.prism.core.review.internal;

import dev.prism.core.identity.Permission;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.review.internal.TaskRouting.Queue;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who may do what in review. Code checks permissions, never roles. */
final class ReviewAccess {

    private ReviewAccess() {}

    static UUID actor() {
        return CurrentActor.require().userId();
    }

    static boolean has(Permission permission) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission.name()::equals);
    }

    static Permission permissionFor(Queue queue) {
        return switch (queue) {
            case REVIEW -> Permission.REVIEW_QUEUE;
            case SENIOR_REVIEW -> Permission.SENIOR_REVIEW_QUEUE;
            case INVESTIGATION -> Permission.INVESTIGATION_QUEUE;
        };
    }

    static boolean canWork(Queue queue) {
        return has(permissionFor(queue));
    }

    static Set<Queue> workableQueues() {
        Set<Queue> queues = EnumSet.noneOf(Queue.class);
        for (Queue queue : Queue.values()) {
            if (canWork(queue)) {
                queues.add(queue);
            }
        }
        return queues;
    }

    static boolean anyReviewRole() {
        return !workableQueues().isEmpty();
    }
}
