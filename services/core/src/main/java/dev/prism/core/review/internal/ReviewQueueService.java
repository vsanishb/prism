package dev.prism.core.review.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.ReviewSupport;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.review.ReviewQueue;
import dev.prism.core.review.internal.ReviewTaskStore.QueueCount;
import dev.prism.core.review.internal.ReviewTaskStore.Task;
import dev.prism.core.review.internal.TaskRouting.Queue;
import dev.prism.core.review.internal.TaskRouting.Route;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewQueueService implements ReviewQueue {

    private static final int LIST_LIMIT = 100;

    private final ReviewTaskStore tasks;
    private final ReviewRecordStore records;
    private final TaskOpener opener;
    private final ClaimDirectory claims;
    private final ReviewSupport intelligence;
    private final AuditRecorder audit;
    private final BigDecimal authorityLimit;

    ReviewQueueService(ReviewTaskStore tasks, ReviewRecordStore records, TaskOpener opener, ClaimDirectory claims,
            ReviewSupport intelligence, AuditRecorder audit, PrismProperties properties) {
        this.tasks = tasks;
        this.records = records;
        this.opener = opener;
        this.claims = claims;
        this.intelligence = intelligence;
        this.audit = audit;
        this.authorityLimit = properties.review().reviewerAuthorityLimit();
    }

    // --- For the workflow, which acts as the system ---

    @Override
    @Transactional
    public void openTask(UUID claimId) {
        ClaimRecord claim = claims.get(claimId);
        if (!"IN_REVIEW".equals(claim.status()) || tasks.lockLive(claimId).isPresent()) {
            return;
        }
        String context = null;
        Optional<UUID> request = records.lockOpenRequest(claimId);
        if (request.isPresent()) {
            records.closeRequest(request.get(), "FULFILLED", null);
            audit.record(AuditEntry.of("claim:" + claimId, AuditAction.INFORMATION_REQUEST_FULFILLED, AuditOutcome.SUCCESS)
                    .target("CLAIM", claimId));
            context = "INFORMATION_RECEIVED";
        } else if (tasks.lastCompleted(claimId).map(Task::completion).filter("REANALYSIS_REQUESTED"::equals).isPresent()) {
            context = "REANALYZED";
        }
        opener.open(claimId, route(claim, context), null);
    }

    /** Also used by actions that send a claim back to the queues outside the workflow. */
    Route route(ClaimRecord claim, String context) {
        return TaskRouting.ready(intelligence.current(claim.id()).riskBand(), records.investigated(claim.id()),
                claim.claimedAmount(), authorityLimit, context);
    }

    @Override
    @Transactional
    public void cancelLiveTask(UUID claimId) {
        tasks.lockLive(claimId).ifPresent(task -> {
            if (tasks.cancel(task.id())) {
                audit.record(AuditEntry.of("claim:" + claimId, AuditAction.REVIEW_TASK_CANCELLED, AuditOutcome.SUCCESS)
                        .target("REVIEW_TASK", task.id()));
            }
        });
    }

    // --- For reviewers ---

    record TaskRow(
            UUID id,
            UUID claimId,
            String claimNumber,
            String patientName,
            BigDecimal claimedAmount,
            String currency,
            String claimStatus,
            Queue queue,
            String reason,
            String status,
            String assigneeName,
            boolean mine,
            String riskBand,
            Instant createdAt,
            Instant claimedAt) {}

    record QueueSummary(Queue queue, int open) {}

    record TaskLists(List<QueueSummary> queues, List<TaskRow> mine, List<TaskRow> available, BigDecimal authorityLimit) {}

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional(readOnly = true)
    public TaskLists lists() {
        Set<Queue> queues = ReviewAccess.workableQueues();
        UUID me = ReviewAccess.actor();
        List<QueueCount> counts = tasks.counts(queues);
        return new TaskLists(
                queues.stream()
                        .map(queue -> new QueueSummary(queue, counts.stream()
                                .filter(count -> count.queue() == queue)
                                .mapToInt(QueueCount::open)
                                .sum()))
                        .toList(),
                tasks.claimedBy(me).stream().map(task -> row(task, me)).toList(),
                tasks.available(queues, LIST_LIMIT).stream().map(task -> row(task, me)).toList(),
                authorityLimit);
    }

    private TaskRow row(Task task, UUID me) {
        ClaimRecord claim = claims.get(task.claimId());
        return new TaskRow(task.id(), claim.id(), claim.claimNumber(), claim.patientName(), claim.claimedAmount(), claim.currency(),
                claim.status(), task.queue(), task.reason(), task.status(), task.assigneeName(), me.equals(task.assigneeId()),
                intelligence.current(claim.id()).riskBand(), task.createdAt(), task.claimedAt());
    }

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public UUID claim(UUID taskId) {
        Task task = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task not found."));
        UUID me = ReviewAccess.actor();
        if (!ReviewAccess.canWork(task.queue())) {
            throw ApiException.forbidden("queue_not_permitted", "You cannot work tasks in this queue.");
        }
        ClaimRecord claim = claims.get(task.claimId());
        ReviewGuards.requireNotCreator(claim, me, audit);
        if (!tasks.claim(taskId, me)) {
            throw ApiException.conflict("task_taken", "Someone else has taken this task, or it has finished.");
        }
        audit.record(AuditEntry.of("claim:" + task.claimId(), AuditAction.REVIEW_TASK_CLAIMED, AuditOutcome.SUCCESS)
                .target("REVIEW_TASK", taskId));
        return task.claimId();
    }

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public void release(UUID taskId) {
        Task task = tasks.find(taskId).orElseThrow(() -> ApiException.notFound("Task not found."));
        if (!tasks.release(taskId, ReviewAccess.actor())) {
            throw ApiException.conflict("not_your_task", "Only the person working a task can release it.");
        }
        audit.record(AuditEntry.of("claim:" + task.claimId(), AuditAction.REVIEW_TASK_RELEASED, AuditOutcome.SUCCESS)
                .target("REVIEW_TASK", taskId));
    }

    /** Takes the oldest task this user may work, or reports that there is none. */
    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public Optional<UUID> next() {
        UUID me = ReviewAccess.actor();
        return tasks.lockNext(ReviewAccess.workableQueues(), me).map(taskId -> {
            Task task = tasks.find(taskId).orElseThrow();
            tasks.claim(taskId, me);
            audit.record(AuditEntry.of("claim:" + task.claimId(), AuditAction.REVIEW_TASK_CLAIMED, AuditOutcome.SUCCESS)
                    .target("REVIEW_TASK", taskId));
            return task.claimId();
        });
    }
}
