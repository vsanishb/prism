package dev.prism.core.review.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.claim.ClaimReview;
import dev.prism.core.identity.Permission;
import dev.prism.core.intelligence.ReviewSupport;
import dev.prism.core.intelligence.ReviewSupport.CurrentRecords;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.review.internal.ReviewRecordStore.NewOutcome;
import dev.prism.core.review.internal.ReviewRecordStore.Outcome;
import dev.prism.core.review.internal.ReviewTaskStore.Task;
import dev.prism.core.review.internal.TaskRouting.Queue;
import dev.prism.core.review.internal.TaskRouting.Route;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything a person does in review. Each action is one transaction: the record, the task, the
 * claim's status, the audit event, and any workflow signal commit together or not at all (D-091).
 */
@Service
class ReviewActionService {

    static final Set<String> DOCUMENT_TYPES = Set.of(
            "CLAIM_FORM", "HOSPITAL_BILL", "ITEMIZED_INVOICE", "DISCHARGE_SUMMARY", "PRESCRIPTION", "DIAGNOSTIC_REPORT", "OTHER");
    static final Set<String> DECISIONS = Set.of("APPROVED", "PARTIALLY_APPROVED", "DENIED");
    static final Set<String> AGREEMENT = Set.of("AGREED", "PARTLY_AGREED", "DISAGREED", "NOT_USED");
    static final Set<String> FINDINGS = Set.of("NO_CONCERN", "INCONCLUSIVE", "CONFIRMED_FRAUD");
    private static final Set<String> REVIEWABLE = Set.of("IN_REVIEW", "ESCALATED");

    private final ReviewTaskStore tasks;
    private final ReviewRecordStore records;
    private final TaskOpener opener;
    private final ReviewQueueService queues;
    private final ClaimDirectory claims;
    private final ClaimReview claimReview;
    private final ReviewSupport intelligence;
    private final Outbox outbox;
    private final AuditRecorder audit;
    private final BigDecimal authorityLimit;

    ReviewActionService(ReviewTaskStore tasks, ReviewRecordStore records, TaskOpener opener, ReviewQueueService queues,
            ClaimDirectory claims, ClaimReview claimReview, ReviewSupport intelligence, Outbox outbox, AuditRecorder audit,
            PrismProperties properties) {
        this.tasks = tasks;
        this.records = records;
        this.opener = opener;
        this.queues = queues;
        this.claims = claims;
        this.claimReview = claimReview;
        this.intelligence = intelligence;
        this.outbox = outbox;
        this.audit = audit;
        this.authorityLimit = properties.review().reviewerAuthorityLimit();
    }

    /** The claim's live task, which the current user must hold. The ID guards against acting on a stale screen. */
    private Task ownTask(UUID claimId, UUID taskId) {
        Task live = tasks.lockLive(claimId)
                .orElseThrow(() -> ApiException.conflict("no_task", "This claim has no task in progress. Reload the page."));
        if (!live.id().equals(taskId)) {
            throw ApiException.conflict("task_changed", "This claim's task has changed since you opened it. Reload the page.");
        }
        if (!"CLAIMED".equals(live.status()) || !ReviewAccess.actor().equals(live.assigneeId())) {
            throw ApiException.conflict("not_your_task", "Take the task before acting on it.");
        }
        return live;
    }

    private void record(UUID claimId, AuditAction action, AuditEntryDetail... details) {
        AuditEntry entry = AuditEntry.of("claim:" + claimId, action, AuditOutcome.SUCCESS).target("CLAIM", claimId);
        for (AuditEntryDetail detail : details) {
            entry = entry.detail(detail.key(), detail.value());
        }
        audit.record(entry);
    }

    private record AuditEntryDetail(AuditDetail key, Object value) {}

    private static String text(String value, int min, int max, String code, String message) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() < min || clean.length() > max) {
            throw ApiException.invalid(code, message);
        }
        return clean;
    }

    // --- Notes ---

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public void addNote(UUID claimId, String body) {
        claims.visible(claimId);
        String note = text(body, 1, 4000, "invalid_note", "Write a note of up to 4,000 characters.");
        records.addNote(claimId, tasks.live(claimId).map(Task::id).orElse(null), ReviewAccess.actor(), note);
        record(claimId, AuditAction.REVIEW_NOTE_ADDED);
    }

    // --- Information requests ---

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public void requestInformation(UUID claimId, UUID taskId, List<String> documentTypes, String message) {
        ClaimRecord claim = claims.visible(claimId);
        Task task = ownTask(claimId, taskId);
        Set<String> types = new LinkedHashSet<>(documentTypes == null ? List.of() : documentTypes);
        if (types.isEmpty() || !DOCUMENT_TYPES.containsAll(types)) {
            throw ApiException.invalid("invalid_document_types", "Choose at least one claim document type.");
        }
        String clean = text(message, 1, 2000, "invalid_message", "Say what is needed, in up to 2,000 characters.");
        if (!REVIEWABLE.contains(claim.status()) || records.lockOpenRequest(claimId).isPresent()) {
            throw ApiException.conflict("cannot_request", "This claim cannot take a new request for information now.");
        }
        records.request(claimId, task.id(), ReviewAccess.actor(), List.copyOf(types), clean);
        tasks.complete(task.id(), ReviewAccess.actor(), "INFORMATION_REQUESTED");
        claimReview.awaitRequestedInformation(claimId);
        record(claimId, AuditAction.INFORMATION_REQUESTED);
    }

    /** Sends the claim back to the queues without the documents, for example when the request was a mistake. */
    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE', 'INVESTIGATION_QUEUE')")
    @Transactional
    public void withdrawRequest(UUID claimId, UUID requestId) {
        ClaimRecord claim = claims.visible(claimId);
        UUID open = records.lockOpenRequest(claimId)
                .filter(requestId::equals)
                .orElseThrow(() -> ApiException.conflict("no_open_request", "This request is no longer open. Reload the page."));
        if (!"AWAITING_INFORMATION".equals(claim.status())) {
            throw ApiException.conflict("claim_processing", "The claim is being processed. Wait until it returns to review.");
        }
        records.closeRequest(open, "CANCELLED", ReviewAccess.actor());
        record(claimId, AuditAction.INFORMATION_REQUEST_WITHDRAWN);
        opener.open(claimId, queues.route(claim, "REQUEST_CANCELLED"), null);
    }

    // --- Escalation and investigation ---

    @PreAuthorize("hasAnyAuthority('REVIEW_QUEUE', 'SENIOR_REVIEW_QUEUE')")
    @Transactional
    public void escalate(UUID claimId, UUID taskId, String target, String note) {
        claims.visible(claimId);
        Task task = ownTask(claimId, taskId);
        Queue to;
        try {
            to = Queue.valueOf(target == null ? "" : target);
        } catch (IllegalArgumentException failure) {
            throw ApiException.invalid("invalid_target", "Escalate to senior review or investigation.");
        }
        if (!TaskRouting.canEscalate(task.queue(), to)) {
            throw ApiException.invalid("invalid_target", "A task can only be escalated to a higher queue.");
        }
        String reason = text(note, 10, 2000, "invalid_note", "Say why, in 10 to 2,000 characters.");
        tasks.complete(task.id(), ReviewAccess.actor(), "ESCALATED");
        opener.open(claimId, new Route(to, "ESCALATED"), reason);
    }

    /** Only an investigator records a finding. Confirmed fraud exists in Prism only as this human record. */
    @PreAuthorize("hasAuthority('INVESTIGATION_QUEUE')")
    @Transactional
    public void recordFinding(UUID claimId, UUID taskId, String finding, String rationale) {
        ClaimRecord claim = claims.visible(claimId);
        Task task = ownTask(claimId, taskId);
        if (task.queue() != Queue.INVESTIGATION) {
            throw ApiException.conflict("not_an_investigation", "Findings are recorded on investigation tasks.");
        }
        if (!FINDINGS.contains(finding)) {
            throw ApiException.invalid("invalid_finding", "Choose a finding.");
        }
        String clean = text(rationale, 10, 4000, "invalid_rationale", "Explain the finding, in 10 to 4,000 characters.");
        records.addFinding(claimId, task.id(), ReviewAccess.actor(), finding, clean);
        tasks.complete(task.id(), ReviewAccess.actor(), "INVESTIGATION_RECORDED");
        record(claimId, AuditAction.INVESTIGATION_FINDING_RECORDED, new AuditEntryDetail(AuditDetail.FINDING, finding));
        opener.open(claimId, TaskRouting.afterInvestigation(finding, claim.claimedAmount(), authorityLimit), null);
    }

    // --- Outcomes ---

    @PreAuthorize("hasAuthority('OUTCOME_RECORD')")
    @Transactional
    public UUID recordOutcome(UUID claimId, UUID taskId, String decision, BigDecimal approvedAmount, String rationale,
            String aiAgreement) {
        ClaimRecord claim = claims.visible(claimId);
        UUID me = ReviewAccess.actor();
        ReviewGuards.requireNotCreator(claim, me, audit);
        Task task = ownTask(claimId, taskId);
        if (task.queue() == Queue.INVESTIGATION) {
            throw ApiException.conflict("investigation_first", "An investigation task records a finding. A reviewer decides afterwards.");
        }
        BigDecimal amount = approved(decision, approvedAmount, claim);
        requireAuthority(claim, amount);
        String clean = text(rationale, 10, 4000, "invalid_rationale", "Give the reasons for the decision, in 10 to 4,000 characters.");
        if (!AGREEMENT.contains(aiAgreement)) {
            throw ApiException.invalid("invalid_agreement", "Say how the AI analysis compared with your decision.");
        }
        CurrentRecords current = intelligence.current(claimId);
        UUID outcome = records.addOutcome(new NewOutcome(claimId, task.id(), decision, amount, claim.currency(), clean, aiAgreement,
                current.consolidationId(), current.policyAssessmentId(), current.riskAssessmentId(), me, null));
        tasks.complete(task.id(), me, "OUTCOME_RECORDED");
        claimReview.decide(claimId);
        outbox.append(Outbox.Event.CLAIM_DECIDED, claimId);
        record(claimId, AuditAction.CLAIM_OUTCOME_RECORDED, new AuditEntryDetail(AuditDetail.DECISION, decision));
        return outcome;
    }

    /**
     * A senior reviewer's correction of a recorded outcome: a new record that supersedes it, by someone
     * other than the person who recorded it. The original stays as it was.
     */
    @PreAuthorize("hasAuthority('OUTCOME_REVISE')")
    @Transactional
    public UUID reviseOutcome(UUID claimId, String decision, BigDecimal approvedAmount, String rationale, String aiAgreement) {
        ClaimRecord claim = claims.visible(claimId);
        UUID me = ReviewAccess.actor();
        ReviewGuards.requireNotCreator(claim, me, audit);
        if (!"DECIDED".equals(claim.status())) {
            throw ApiException.conflict("not_decided", "Only a decided claim's outcome can be revised.");
        }
        Outcome current = records.outcomes(claimId).stream().filter(Outcome::current).findFirst()
                .orElseThrow(() -> ApiException.conflict("not_decided", "This claim has no outcome to revise."));
        if (me.equals(current.decidedById())) {
            ReviewGuards.deny(claimId, "own_outcome", audit);
            throw ApiException.forbidden("separation_of_duties", "Someone other than the person who recorded an outcome must revise it.");
        }
        BigDecimal amount = approved(decision, approvedAmount, claim);
        String clean = text(rationale, 10, 4000, "invalid_rationale", "Give the reasons for the revision, in 10 to 4,000 characters.");
        if (!AGREEMENT.contains(aiAgreement)) {
            throw ApiException.invalid("invalid_agreement", "Say how the AI analysis compared with your decision.");
        }
        CurrentRecords basis = intelligence.current(claimId);
        UUID outcome;
        try {
            outcome = records.addOutcome(new NewOutcome(claimId, null, decision, amount, claim.currency(), clean, aiAgreement,
                    basis.consolidationId(), basis.policyAssessmentId(), basis.riskAssessmentId(), me, current.id()));
        } catch (org.springframework.dao.DuplicateKeyException race) {
            throw ApiException.conflict("outcome_changed", "Someone revised this outcome first. Reload the page.");
        }
        record(claimId, AuditAction.CLAIM_OUTCOME_REVISED, new AuditEntryDetail(AuditDetail.DECISION, decision));
        return outcome;
    }

    /** APPROVED pays the claimed amount, PARTIALLY_APPROVED a smaller one, DENIED nothing. */
    static BigDecimal approved(String decision, BigDecimal requested, ClaimRecord claim) {
        if (!DECISIONS.contains(decision)) {
            throw ApiException.invalid("invalid_decision", "Choose approved, partly approved, or denied.");
        }
        return switch (decision) {
            case "APPROVED" -> {
                if (requested != null && requested.compareTo(claim.claimedAmount()) != 0) {
                    throw ApiException.invalid("invalid_amount", "An approval is for the claimed amount. Choose partly approved for less.");
                }
                yield claim.claimedAmount();
            }
            case "PARTIALLY_APPROVED" -> {
                if (requested == null || requested.signum() <= 0 || requested.compareTo(claim.claimedAmount()) >= 0
                        || requested.scale() > 2) {
                    throw ApiException.invalid("invalid_amount", "Enter an approved amount above zero and below the claimed amount.");
                }
                yield requested.setScale(2);
            }
            default -> {
                if (requested != null) {
                    throw ApiException.invalid("invalid_amount", "A denial has no approved amount.");
                }
                yield null;
            }
        };
    }

    private void requireAuthority(ClaimRecord claim, BigDecimal amount) {
        if (amount != null && amount.compareTo(authorityLimit) > 0 && !ReviewAccess.has(Permission.SENIOR_REVIEW_QUEUE)) {
            ReviewGuards.deny(claim.id(), "above_authority_limit", audit);
            throw ApiException.forbidden("above_authority_limit", "This amount is above your authority limit. Escalate to senior review.");
        }
    }

    // --- Fact corrections ---

    @PreAuthorize("hasAuthority('OUTCOME_RECORD')")
    @Transactional
    public UUID correctFact(UUID claimId, UUID taskId, UUID factId, String value, String reason) {
        claims.visible(claimId);
        ownTask(claimId, taskId);
        return intelligence.correctFact(claimId, factId, value, reason, ReviewAccess.actor());
    }

    /** Runs consolidation, analysis, and screening again with the corrections. The reviewer's choice, never automatic. */
    @PreAuthorize("hasAuthority('OUTCOME_RECORD')")
    @Transactional
    public void requestReanalysis(UUID claimId, UUID taskId) {
        claims.visible(claimId);
        Task task = ownTask(claimId, taskId);
        if (intelligence.corrections(claimId).stream().allMatch(ReviewSupport.Correction::applied)) {
            throw ApiException.conflict("nothing_to_reanalyze", "There are no new corrections to analyze.");
        }
        tasks.complete(task.id(), ReviewAccess.actor(), "REANALYSIS_REQUESTED");
        outbox.append(Outbox.Event.CLAIM_REANALYSIS_REQUESTED, claimId);
        record(claimId, AuditAction.CLAIM_REANALYSIS_REQUESTED);
    }
}
