package dev.prism.core.policy.internal;

import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.web.Page;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import dev.prism.core.policy.internal.PolicyService.PolicyDetail;
import dev.prism.core.policy.internal.PolicyService.PolicySummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyService service;

    PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    Page<PolicySummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(search, cursor, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/{id}")
    PolicyDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PolicySummary create(@Valid @RequestBody CreatePolicyRequest body) {
        return service.create(body.policyNumber(), body.productName(), body.policyholderName());
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    VersionRef addVersion(@PathVariable UUID id, @Valid @RequestBody AddVersionRequest body) {
        return service.addVersion(id, body.effectiveFrom(), body.effectiveTo(), body.sumInsured());
    }

    @PostMapping("/{id}/versions/{versionId}/wording")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView attachWording(
            @PathVariable UUID id, @PathVariable UUID versionId, @RequestPart("file") MultipartFile file) {
        return service.attachWording(id, versionId, file);
    }

    record CreatePolicyRequest(
            @NotBlank(message = "Enter a policy number.") @Size(max = 60) String policyNumber,
            @NotBlank(message = "Enter a product name.") @Size(max = 200) String productName,
            @NotBlank(message = "Enter the policyholder's name.") @Size(max = 200) String policyholderName) {}

    record AddVersionRequest(
            @NotNull(message = "Enter a start date.") LocalDate effectiveFrom,
            @NotNull(message = "Enter an end date.") LocalDate effectiveTo,
            @NotNull(message = "Enter the sum insured.")
                    @DecimalMin(value = "0.01", message = "The sum insured must be more than zero.")
                    @DecimalMax(value = "999999999999.99", message = "This amount is too large.")
                    @Digits(integer = 12, fraction = 2, message = "Use at most two decimal places.")
                    BigDecimal sumInsured) {}
}
