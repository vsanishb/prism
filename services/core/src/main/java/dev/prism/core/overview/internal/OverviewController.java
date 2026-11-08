package dev.prism.core.overview.internal;

import dev.prism.core.overview.internal.OverviewReader.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OverviewController {

    private final OverviewReader reader;

    OverviewController(OverviewReader reader) {
        this.reader = reader;
    }

    /** Needs CLAIM_READ. Staff without it see the home page without these figures. */
    @GetMapping("/api/v1/overview")
    Overview overview() {
        return reader.read();
    }
}
