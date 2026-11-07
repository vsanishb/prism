package dev.prism.core.intelligence.internal;

import dev.prism.core.intelligence.internal.ClaimFactsService.ClaimFacts;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ClaimFactsController {

    private final ClaimFactsService service;

    ClaimFactsController(ClaimFactsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/claims/{id}/facts")
    ClaimFacts facts(@PathVariable UUID id) {
        return service.read(id);
    }
}
