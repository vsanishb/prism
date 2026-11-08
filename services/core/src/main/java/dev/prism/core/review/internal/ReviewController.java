package dev.prism.core.review.internal;

import dev.prism.core.platform.web.ApiException;
import dev.prism.core.review.internal.EvidenceChainReader.Chain;
import dev.prism.core.review.internal.ReviewQueueService.TaskLists;
import dev.prism.core.review.internal.ReviewWorkspaceReader.Workspace;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Review queues and actions. Authorization is in the services, which every path goes through. */
@RestController
@Validated
class ReviewController {

    private final ReviewQueueService queues;
    private final ReviewActionService actions;
    private final ReviewWorkspaceReader workspace;
    private final EvidenceChainReader chain;

    ReviewController(ReviewQueueService queues, ReviewActionService actions, ReviewWorkspaceReader workspace,
            EvidenceChainReader chain) {
        this.queues = queues;
        this.actions = actions;
        this.workspace = workspace;
        this.chain = chain;
    }

    record ClaimRef(UUID claimId) {}

    // --- Queues ---

    @GetMapping("/api/v1/review/tasks")
    TaskLists tasks() {
        return queues.lists();
    }

    @PostMapping("/api/v1/review/tasks/next")
    ClaimRef next() {
        return queues.next()
                .map(ClaimRef::new)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_task", "No task is waiting in your queues."));
    }

    @PostMapping("/api/v1/review/tasks/{taskId}/claim")
    ClaimRef claim(@PathVariable UUID taskId) {
        return new ClaimRef(queues.claim(taskId));
    }

    @PostMapping("/api/v1/review/tasks/{taskId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID taskId) {
        queues.release(taskId);
    }

    // --- A claim's review ---

    @GetMapping("/api/v1/claims/{claimId}/review")
    Workspace workspace(@PathVariable UUID claimId) {
        return workspace.read(claimId);
    }

    @GetMapping("/api/v1/claims/{claimId}/evidence-chain")
    Chain evidenceChain(@PathVariable UUID claimId) {
        return chain.read(claimId);
    }

    record NoteRequest(String body) {}

    @PostMapping("/api/v1/claims/{claimId}/review/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void note(@PathVariable UUID claimId, @RequestBody NoteRequest body) {
        actions.addNote(claimId, body.body());
    }

    record InformationRequest(@NotNull UUID taskId, List<String> documentTypes, String message) {}

    @PostMapping("/api/v1/claims/{claimId}/review/information-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void requestInformation(@PathVariable UUID claimId, @RequestBody @Validated InformationRequest body) {
        actions.requestInformation(claimId, body.taskId(), body.documentTypes(), body.message());
    }

    @PostMapping("/api/v1/claims/{claimId}/review/information-requests/{requestId}/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdrawRequest(@PathVariable UUID claimId, @PathVariable UUID requestId) {
        actions.withdrawRequest(claimId, requestId);
    }

    record EscalationRequest(@NotNull UUID taskId, String target, String note) {}

    @PostMapping("/api/v1/claims/{claimId}/review/escalation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void escalate(@PathVariable UUID claimId, @RequestBody @Validated EscalationRequest body) {
        actions.escalate(claimId, body.taskId(), body.target(), body.note());
    }

    record FindingRequest(@NotNull UUID taskId, String finding, String rationale) {}

    @PostMapping("/api/v1/claims/{claimId}/review/investigation-findings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void finding(@PathVariable UUID claimId, @RequestBody @Validated FindingRequest body) {
        actions.recordFinding(claimId, body.taskId(), body.finding(), body.rationale());
    }

    record OutcomeRequest(@NotNull UUID taskId, String decision, BigDecimal approvedAmount, String rationale, String aiAgreement) {}

    /** Retrying is safe: a second attempt finds the task finished and is refused, so one outcome is ever recorded. */
    @PostMapping("/api/v1/claims/{claimId}/review/outcome")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> outcome(@PathVariable UUID claimId, @RequestBody @Validated OutcomeRequest body) {
        return Map.of("id", actions.recordOutcome(
                claimId, body.taskId(), body.decision(), body.approvedAmount(), body.rationale(), body.aiAgreement()));
    }

    record RevisionRequest(String decision, BigDecimal approvedAmount, String rationale, String aiAgreement) {}

    @PostMapping("/api/v1/claims/{claimId}/review/outcome/revision")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> revise(@PathVariable UUID claimId, @RequestBody RevisionRequest body) {
        return Map.of("id", actions.reviseOutcome(claimId, body.decision(), body.approvedAmount(), body.rationale(), body.aiAgreement()));
    }

    record CorrectionRequest(@NotNull UUID taskId, @NotNull UUID factId, String value, String reason) {}

    @PostMapping("/api/v1/claims/{claimId}/review/fact-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> correct(@PathVariable UUID claimId, @RequestBody @Validated CorrectionRequest body) {
        return Map.of("id", actions.correctFact(claimId, body.taskId(), body.factId(), body.value(), body.reason()));
    }

    record TaskRef(@NotNull UUID taskId) {}

    @PostMapping("/api/v1/claims/{claimId}/review/reanalysis")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reanalyze(@PathVariable UUID claimId, @RequestBody @Validated TaskRef body) {
        actions.requestReanalysis(claimId, body.taskId());
    }
}
