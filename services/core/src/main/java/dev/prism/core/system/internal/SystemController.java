package dev.prism.core.system.internal;

import dev.prism.core.system.internal.SystemStatusService.SystemStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {

    private final SystemStatusService service;

    SystemController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping("/status")
    SystemStatus status() {
        return service.status();
    }
}
