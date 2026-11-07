package dev.prism.core.workflow.internal;

import dev.prism.core.workflow.internal.ProcessingStatusService.ProcessingView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claims/{id}/processing")
class ClaimProcessingController {

    private final ProcessingStatusService service;

    ClaimProcessingController(ProcessingStatusService service) {
        this.service = service;
    }

    @GetMapping
    ProcessingView view(@PathVariable UUID id) {
        return service.view(id);
    }

    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void retry(@PathVariable UUID id) {
        service.retry(id);
    }
}
