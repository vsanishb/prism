package dev.prism.core.intelligence.internal;

import dev.prism.core.intelligence.internal.AssessmentReader.AssessmentResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssessmentController {

    private final AssessmentReader reader;

    AssessmentController(AssessmentReader reader) {
        this.reader = reader;
    }

    /** The claim's latest policy analysis, or none yet. It assists review and decides nothing. */
    @GetMapping("/api/v1/claims/{claimId}/assessment")
    AssessmentResponse latest(@PathVariable UUID claimId) {
        return reader.latest(claimId);
    }
}
