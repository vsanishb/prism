package dev.prism.core.policyknowledge.internal;

import dev.prism.core.policyknowledge.internal.PolicyKnowledgeService.ReviewRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies/{policyId}/versions/{versionId}/knowledge")
class PolicyKnowledgeController {

    private final PolicyKnowledgeService service;

    PolicyKnowledgeController(PolicyKnowledgeService service) {
        this.service = service;
    }

    @GetMapping
    KnowledgeView view(@PathVariable UUID policyId, @PathVariable UUID versionId) {
        return service.view(policyId, versionId);
    }

    @PostMapping("/values/{valueId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(
            @PathVariable UUID policyId,
            @PathVariable UUID versionId,
            @PathVariable UUID valueId,
            @Valid @RequestBody ReviewBody body) {
        service.review(policyId, versionId, valueId, new ReviewRequest(body.decision(), body.correctedValue(), body.note()));
    }

    @PostMapping("/publication")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publish(@PathVariable UUID policyId, @PathVariable UUID versionId) {
        service.publish(policyId, versionId);
    }

    @PostMapping("/rejection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable UUID policyId, @PathVariable UUID versionId, @Valid @RequestBody RejectBody body) {
        service.reject(policyId, versionId, body.reason());
    }

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retry(@PathVariable UUID policyId, @PathVariable UUID versionId) {
        service.retry(policyId, versionId);
    }

    record ReviewBody(
            @NotBlank(message = "Choose to confirm or correct the value.") String decision,
            @Size(max = 200) String correctedValue,
            @Size(max = 1000) String note) {}

    record RejectBody(@NotBlank(message = "Give a reason for rejecting this version.") @Size(max = 1000) String reason) {}
}
