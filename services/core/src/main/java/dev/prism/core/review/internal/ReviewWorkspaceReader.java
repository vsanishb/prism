package dev.prism.core.review.internal;

import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.identity.Permission;
import dev.prism.core.intelligence.ReviewSupport;
import dev.prism.core.intelligence.ReviewSupport.Correction;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.review.internal.ReviewRecordStore.FindingRecord;
import dev.prism.core.review.internal.ReviewRecordStore.Note;
import dev.prism.core.review.internal.ReviewRecordStore.Outcome;
import dev.prism.core.review.internal.ReviewRecordStore.Request;
import dev.prism.core.review.internal.ReviewTaskStore.Task;
import dev.prism.core.review.internal.TaskRouting.Queue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A claim's review state, and what the current user may do with it. The server enforces each action again. */
@Service
class ReviewWorkspaceReader {

    private final ClaimDirectory claims;
    private final ReviewTaskStore tasks;
    private final ReviewRecordStore records;
    private final ReviewSupport intelligence;
    private final BigDecimal authorityLimit;

    ReviewWorkspaceReader(ClaimDirectory claims, ReviewTaskStore tasks, ReviewRecordStore records, ReviewSupport intelligence,
            PrismProperties properties) {
        this.claims = claims;
        this.tasks = tasks;
        this.records = records;
        this.intelligence = intelligence;
        this.authorityLimit = properties.review().reviewerAuthorityLimit();
    }

    record Workspace(
            Task task,
            boolean taskIsMine,
            List<Task> history,
            List<Note> notes,
            List<Request> requests,
            List<FindingRecord> findings,
            List<Outcome> outcomes,
            List<Correction> corrections,
            String riskBand,
            boolean createdByYou,
            BigDecimal authorityLimit,
            List<String> actions,
            List<String> escalationTargets) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public Workspace read(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        UUID me = ReviewAccess.actor();
        Optional<Task> live = tasks.live(claimId);
        boolean mine = live.map(task -> "CLAIMED".equals(task.status()) && me.equals(task.assigneeId())).orElse(false);
        boolean creator = me.equals(claim.createdBy());
        List<Request> requests = records.requests(claimId);
        List<Outcome> outcomes = records.outcomes(claimId);
        List<Correction> corrections = intelligence.corrections(claimId);

        List<String> actions = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        if (ReviewAccess.anyReviewRole()) {
            actions.add("ADD_NOTE");
        }
        live.ifPresent(task -> {
            if ("OPEN".equals(task.status()) && ReviewAccess.canWork(task.queue()) && !creator) {
                actions.add("CLAIM_TASK");
            }
        });
        if (mine) {
            Task task = live.orElseThrow();
            actions.add("RELEASE_TASK");
            actions.add("REQUEST_INFORMATION");
            if (task.queue() == Queue.INVESTIGATION) {
                actions.add("RECORD_FINDING");
            } else {
                for (Queue target : Queue.values()) {
                    if (TaskRouting.canEscalate(task.queue(), target)) {
                        targets.add(target.name());
                    }
                }
                if (!targets.isEmpty()) {
                    actions.add("ESCALATE");
                }
                if (ReviewAccess.has(Permission.OUTCOME_RECORD) && !creator) {
                    actions.add("RECORD_OUTCOME");
                    actions.add("CORRECT_FACT");
                    if (corrections.stream().anyMatch(correction -> !correction.applied())) {
                        actions.add("REQUEST_REANALYSIS");
                    }
                }
            }
        }
        if ("AWAITING_INFORMATION".equals(claim.status()) && ReviewAccess.anyReviewRole()
                && requests.stream().anyMatch(request -> "OPEN".equals(request.status()))) {
            actions.add("WITHDRAW_REQUEST");
        }
        Optional<Outcome> current = outcomes.stream().filter(Outcome::current).findFirst();
        if ("DECIDED".equals(claim.status()) && ReviewAccess.has(Permission.OUTCOME_REVISE) && !creator
                && current.map(outcome -> !me.equals(outcome.decidedById())).orElse(false)) {
            actions.add("REVISE_OUTCOME");
        }
        return new Workspace(live.orElse(null), mine, tasks.history(claimId), records.notes(claimId), requests,
                records.findings(claimId), outcomes, corrections, intelligence.current(claimId).riskBand(), creator, authorityLimit,
                actions, targets);
    }
}
