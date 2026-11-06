package dev.prism.core.claim.internal;

import dev.prism.core.audit.AuditTimeline.TimelineEntry;
import dev.prism.core.claim.internal.ClaimEntity.Details;
import dev.prism.core.claim.internal.ClaimEntity.WithdrawalReason;
import dev.prism.core.claim.internal.ClaimService.ClaimDetail;
import dev.prism.core.claim.internal.ClaimService.ClaimSummary;
import dev.prism.core.document.DocumentType;
import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.web.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/claims")
class ClaimController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ClaimService service;

    ClaimController(ClaimService service) {
        this.service = service;
    }

    @GetMapping
    Page<ClaimSummary> list(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "25") int limit) {
        return service.list(status, search, cursor, Math.clamp(limit, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/{id}")
    ClaimDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/timeline")
    List<TimelineEntry> timeline(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ClaimDetail create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @Valid @RequestBody ClaimRequest body) {
        return service.create(body.toDetails(), idempotencyKey);
    }

    @PutMapping("/{id}")
    ClaimDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateClaimRequest body) {
        return service.update(id, body.claim().toDetails(), body.version());
    }

    @PostMapping("/{id}/submission")
    ClaimDetail submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/withdrawal")
    ClaimDetail withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest body) {
        return service.withdraw(id, body.reason());
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView addDocument(
            @PathVariable UUID id,
            @RequestParam DocumentType documentType,
            @RequestPart("file") MultipartFile file) {
        return service.addDocument(id, documentType, file);
    }

    @PostMapping("/{id}/documents/{documentId}/replacement")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentView replaceDocument(
            @PathVariable UUID id, @PathVariable UUID documentId, @RequestPart("file") MultipartFile file) {
        return service.replaceDocument(id, documentId, file);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        service.removeDocument(id, documentId);
    }

    record ClaimRequest(
            @NotNull(message = "Choose a claim type.") ClaimEntity.Type type,
            @NotNull(message = "Choose a policy.") UUID policyId,
            @NotBlank(message = "Enter the claimant's name.") @Size(max = 200) String claimantName,
            @NotBlank(message = "Enter the patient's name.") @Size(max = 200) String patientName,
            @NotBlank(message = "Enter the hospital.") @Size(max = 200) String hospitalName,
            @NotNull(message = "Enter the admission date.") LocalDate admissionDate,
            @NotNull(message = "Enter the discharge date.") LocalDate dischargeDate,
            @NotNull(message = "Enter the claimed amount.")
                    @DecimalMin(value = "0.01", message = "The amount must be more than zero.")
                    @DecimalMax(value = "999999999999.99", message = "This amount is too large.")
                    @Digits(integer = 12, fraction = 2, message = "Use at most two decimal places.")
                    BigDecimal claimedAmount) {

        Details toDetails() {
            return new Details(
                    type, policyId, claimantName, patientName, hospitalName, admissionDate, dischargeDate, claimedAmount);
        }
    }

    record UpdateClaimRequest(@NotNull @Valid ClaimRequest claim, @NotNull Long version) {}

    record WithdrawRequest(@NotNull(message = "Choose a reason.") WithdrawalReason reason) {}
}
