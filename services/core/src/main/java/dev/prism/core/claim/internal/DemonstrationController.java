package dev.prism.core.claim.internal;

import dev.prism.core.claim.internal.DemonstrationService.Scenario;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DemonstrationController {

    private final DemonstrationService demonstrations;

    DemonstrationController(DemonstrationService demonstrations) {
        this.demonstrations = demonstrations;
    }

    /** The seeded risk demonstrations and whether each is ready to submit. Local environment only. */
    @GetMapping("/api/v1/demonstrations/risk")
    List<Scenario> scenarios() {
        return demonstrations.scenarios();
    }
}
