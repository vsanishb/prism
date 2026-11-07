package dev.prism.core.retrieval.internal;

import dev.prism.core.retrieval.internal.SearchView.IndexView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/policies/{policyId}/versions/{versionId}")
class PolicySearchController {

    private final PolicySearchService service;

    PolicySearchController(PolicySearchService service) {
        this.service = service;
    }

    @GetMapping("/search-index")
    IndexView index(@PathVariable UUID policyId, @PathVariable UUID versionId) {
        return service.index(policyId, versionId);
    }

    @PostMapping("/search-index/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retry(@PathVariable UUID policyId, @PathVariable UUID versionId) {
        service.retry(policyId, versionId);
    }

    /** POST, so that the query stays out of URLs and access logs. */
    @PostMapping("/search")
    SearchView search(@PathVariable UUID policyId, @PathVariable UUID versionId, @Valid @RequestBody SearchBody body) {
        return service.search(policyId, versionId, body.query(), body.types());
    }

    record SearchBody(
            @NotBlank(message = "Enter a question or some words to search for.")
                    @Size(max = 500, message = "Keep the search under 500 characters.")
                    String query,
            @Size(max = 7) List<String> types) {}
}
