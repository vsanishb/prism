package dev.prism.core.intelligence.internal;

import dev.prism.core.intelligence.internal.RiskReader.RiskResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RiskController {

    private final RiskReader reader;

    RiskController(RiskReader reader) {
        this.reader = reader;
    }

    /** The claim's latest risk screening, or none yet. Signals for review: never a finding of fraud. */
    @GetMapping("/api/v1/claims/{claimId}/risk")
    RiskResponse latest(@PathVariable UUID claimId) {
        return reader.latest(claimId);
    }
}
