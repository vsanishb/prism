package dev.prism.core.claim.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.audit.AuditTimeline;
import dev.prism.core.audit.AuditTimeline.TimelineEntry;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.internal.ClaimEntity.Details;
import dev.prism.core.claim.internal.ClaimEntity.WithdrawalReason;
import dev.prism.core.document.DocumentAccessGuard;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentType;
import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.platform.web.Page;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyDirectory.PolicyRef;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class ClaimService implements DocumentAccessGuard, ClaimDirectory {

    private static final UUID LAST_PAGE_BOUND = new UUID(-1L, -1L);
    private static final String CLAIM_MANAGE = "CLAIM_MANAGE";

    private final ClaimRepository claims;
    private final PolicyDirectory policies;
    private final DocumentService documents;
    private final AuditRecorder audit;
    private final AuditTimeline timeline;
    private final Outbox outbox;
    private final String defaultCurrency;

    ClaimService(
            ClaimRepository claims,
            PolicyDirectory policies,
            DocumentService documents,
            AuditRecorder audit,
            AuditTimeline timeline,
            Outbox outbox,
            PrismProperties properties) {
        this.claims = claims;
        this.policies = policies;
        this.documents = documents;
        this.audit = audit;
        this.timeline = timeline;
        this.outbox = outbox;
        this.defaultCurrency = properties.defaultCurrency();
    }

    record ClaimSummary(
            UUID id,
            String claimNumber,
            ClaimStatus status,
            ClaimEntity.Type type,
            String patientName,
            String hospitalName,
            LocalDate admissionDate,
            BigDecimal claimedAmount,
            String currency,
            Instant createdAt) {
        static ClaimSummary of(ClaimEntity claim) {
            return new ClaimSummary(
                    claim.id(), claim.claimNumber(), claim.status(), claim.type(), claim.patientName(), claim.hospitalName(),
                    claim.admissionDate(), claim.claimedAmount(), claim.currency(), claim.createdAt());
        }
    }

    record ClaimDetail(
            ClaimSummary summary,
            String claimantName,
            LocalDate dischargeDate,
            PolicyRef policy,
            VersionRef policyVersion,
            boolean policyVersionFixed,
            WithdrawalReason withdrawalReason,
            Instant submittedAt,
            long version,
            List<DocumentView> documents,
            List<String> submissionIssues,
            Set<Action> availableActions) {}

    enum Action {
        EDIT,
        SUBMIT,
        WITHDRAW,
        ADD_DOCUMENT,
        REMOVE_DOCUMENT
    }

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public Page<ClaimSummary> list(ClaimStatus status, String search, UUID before, int limit) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<ClaimEntity> rows = claims.page(
                before == null ? LAST_PAGE_BOUND : before, canManage(), status != null, status, term, Limit.of(limit + 1));
        boolean hasMore = rows.size() > limit;
        List<ClaimEntity> items = hasMore ? rows.subList(0, limit) : rows;
        return new Page<>(items.stream().map(ClaimSummary::of).toList(), hasMore ? items.getLast().id().toString() : null);
    }

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public ClaimDetail detail(UUID claimId) {
        return toDetail(requireVisible(claimId));
    }

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public List<TimelineEntry> history(UUID claimId) {
        // These are recorded for auditors, and would drown out the claim's history here.
        Set<AuditAction> hidden = Set.of(AuditAction.DOCUMENT_VIEWED, AuditAction.DOCUMENT_SENT_FOR_EXTRACTION);
        return timeline.forStream(stream(requireVisible(claimId).id()), hidden);
    }

    /** A repeated key returns the first result, so that a retried request cannot create a duplicate. */
    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public ClaimDetail create(Details details, UUID idempotencyKey) {
        UUID actor = CurrentActor.require().userId();
        Optional<ClaimEntity> existing = claims.findByCreatedByAndIdempotencyKey(actor, idempotencyKey);
        if (existing.isPresent()) {
            return toDetail(existing.get());
        }
        validate(details);
        String number = "CLM-%d-%06d".formatted(Year.now(ZoneOffset.UTC).getValue(), claims.nextClaimNumber());
        ClaimEntity claim = claims.save(new ClaimEntity(number, details, defaultCurrency, idempotencyKey, actor));
        audit.record(AuditEntry.of(stream(claim.id()), AuditAction.CLAIM_CREATED, AuditOutcome.SUCCESS)
                .target("CLAIM", claim.id()));
        return toDetail(claim);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public ClaimDetail update(UUID claimId, Details details, long expectedVersion) {
        ClaimEntity claim = requireLocked(claimId);
        if (claim.status() != ClaimStatus.DRAFT) {
            throw ApiException.conflict("not_editable", "Only draft claims can be edited.");
        }
        if (claim.version() != expectedVersion) {
            throw ApiException.conflict("concurrent_change", "This claim changed while you were editing it. Reload and try again.");
        }
        validate(details);
        claim.apply(details);
        audit.record(AuditEntry.of(stream(claim.id()), AuditAction.CLAIM_UPDATED, AuditOutcome.SUCCESS)
                .target("CLAIM", claim.id()));
        return toDetail(claim);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public ClaimDetail submit(UUID claimId) {
        ClaimEntity claim = requireLocked(claimId);
        if (claim.status() == ClaimStatus.SUBMITTED) {
            return toDetail(claim);
        }
        if (claim.status() != ClaimStatus.DRAFT) {
            throw ApiException.conflict("invalid_transition", "This action is not available for a claim in its current state.");
        }
        List<String> issues = submissionIssues(claim);
        if (!issues.isEmpty()) {
            throw ApiException.invalid("not_ready", issues.getFirst());
        }
        VersionRef version = policies.versionCovering(claim.policyId(), claim.admissionDate()).orElseThrow();
        claim.submit(version.id(), CurrentActor.require().userId(), Instant.now());
        audit.record(AuditEntry.of(stream(claim.id()), AuditAction.CLAIM_SUBMITTED, AuditOutcome.SUCCESS)
                .target("CLAIM", claim.id())
                .detail(AuditDetail.POLICY_VERSION_ID, version.id()));
        outbox.append(Outbox.Event.CLAIM_SUBMITTED, claim.id());
        return toDetail(claim);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public ClaimDetail withdraw(UUID claimId, WithdrawalReason reason) {
        ClaimEntity claim = requireLocked(claimId);
        ClaimStatus before = claim.status();
        claim.withdraw(reason);
        audit.record(AuditEntry.of(stream(claim.id()), AuditAction.CLAIM_WITHDRAWN, AuditOutcome.SUCCESS)
                .target("CLAIM", claim.id())
                .detail(AuditDetail.STATUS_BEFORE, before)
                .detail(AuditDetail.REASON, reason));
        if (before != ClaimStatus.DRAFT) {
            outbox.append(Outbox.Event.CLAIM_WITHDRAWN, claim.id());
        }
        return toDetail(claim);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public DocumentView addDocument(UUID claimId, DocumentType type, MultipartFile file) {
        ClaimEntity claim = requireOpen(claimId);
        DocumentView stored = documents.store(ownerOf(claim), requireClaimType(type), file);
        announceDocumentChange(claim);
        return stored;
    }

    /** For the synthetic seeder, which reads documents from disk. */
    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public void addDocument(UUID claimId, DocumentType type, Path file) {
        documents.store(ownerOf(requireOpen(claimId)), requireClaimType(type), file);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public DocumentView replaceDocument(UUID claimId, UUID documentId, MultipartFile file) {
        ClaimEntity claim = requireOpen(claimId);
        DocumentView stored = documents.replace(ownerOf(claim), documentId, file);
        announceDocumentChange(claim);
        return stored;
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public void removeDocument(UUID claimId, UUID documentId) {
        ClaimEntity claim = requireLocked(claimId);
        if (claim.status() != ClaimStatus.DRAFT) {
            throw ApiException.conflict("not_editable", "Documents can be removed only while a claim is a draft.");
        }
        documents.remove(ownerOf(claim), documentId);
    }

    @Override
    public DocumentOwner.Type ownerType() {
        return DocumentOwner.Type.CLAIM;
    }

    @Override
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public DocumentOwner requireReadable(UUID ownerId) {
        return ownerOf(requireVisible(ownerId));
    }

    @Override
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public ClaimRecord visible(UUID claimId) {
        return toRecord(requireVisible(claimId));
    }

    @Override
    @Transactional(readOnly = true)
    public ClaimRecord get(UUID claimId) {
        return toRecord(claims.findById(claimId).orElseThrow(ClaimService::claimNotFound));
    }

    private static ClaimRecord toRecord(ClaimEntity claim) {
        return new ClaimRecord(
                claim.id(),
                claim.type().name(),
                claim.status().name(),
                claim.status().isTerminal(),
                claim.claimantName(),
                claim.patientName(),
                claim.hospitalName(),
                claim.admissionDate(),
                claim.dischargeDate(),
                claim.claimedAmount(),
                claim.currency());
    }

    /** A draft has no workflow yet: submission will pick up whatever documents it has then. */
    private void announceDocumentChange(ClaimEntity claim) {
        if (claim.status() != ClaimStatus.DRAFT) {
            outbox.append(Outbox.Event.CLAIM_DOCUMENTS_CHANGED, claim.id());
        }
    }

    public boolean anyExist() {
        return claims.count() > 0;
    }

    private ClaimDetail toDetail(ClaimEntity claim) {
        PolicyRef policy = policies.findPolicy(claim.policyId()).orElseThrow();
        boolean fixed = claim.policyVersionId() != null;
        VersionRef version = fixed
                ? policies.findVersion(claim.policyVersionId()).orElseThrow()
                : policies.versionCovering(claim.policyId(), claim.admissionDate()).orElse(null);
        return new ClaimDetail(
                ClaimSummary.of(claim),
                claim.claimantName(),
                claim.dischargeDate(),
                policy,
                version,
                fixed,
                claim.withdrawalReason(),
                claim.submittedAt(),
                claim.version(),
                documents.listFor(DocumentOwner.Type.CLAIM, claim.id()),
                claim.status() == ClaimStatus.DRAFT ? submissionIssues(claim) : List.of(),
                availableActions(claim));
    }

    /** Submission needs the minimum only. Completeness of the evidence is assessed later (M3). */
    private List<String> submissionIssues(ClaimEntity claim) {
        List<String> issues = new ArrayList<>();
        if (policies.versionCovering(claim.policyId(), claim.admissionDate()).isEmpty()) {
            issues.add("No version of this policy covers the admission date.");
        }
        if (documents.countActive(DocumentOwner.Type.CLAIM, claim.id()) == 0) {
            issues.add("Add at least one document.");
        }
        return issues;
    }

    private Set<Action> availableActions(ClaimEntity claim) {
        if (!canManage() || claim.status().isTerminal()) {
            return Set.of();
        }
        Set<Action> actions = new java.util.TreeSet<>(Set.of(Action.ADD_DOCUMENT));
        if (claim.status() == ClaimStatus.DRAFT) {
            actions.addAll(Set.of(Action.EDIT, Action.SUBMIT, Action.REMOVE_DOCUMENT));
        }
        if (claim.status().canBecome(ClaimStatus.WITHDRAWN)) {
            actions.add(Action.WITHDRAW);
        }
        return actions;
    }

    private void validate(Details details) {
        if (policies.findPolicy(details.policyId()).isEmpty()) {
            throw ApiException.invalid("unknown_policy", "Choose a policy from the register.");
        }
        if (details.dischargeDate().isBefore(details.admissionDate())) {
            throw ApiException.invalid("invalid_dates", "The discharge date must not be before the admission date.");
        }
        // One day of tolerance: "today" in India is already tomorrow in UTC for part of each day.
        if (details.admissionDate().isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw ApiException.invalid("invalid_dates", "The admission date must not be in the future.");
        }
    }

    private static DocumentType requireClaimType(DocumentType type) {
        if (type == DocumentType.POLICY_WORDING) {
            throw ApiException.invalid("invalid_document_type", "Choose a claim document type.");
        }
        return type;
    }

    private ClaimEntity requireOpen(UUID claimId) {
        ClaimEntity claim = requireLocked(claimId);
        if (claim.status().isTerminal()) {
            throw ApiException.conflict("claim_closed", "Documents cannot be changed on a closed claim.");
        }
        return claim;
    }

    private ClaimEntity requireLocked(UUID claimId) {
        return claims.lockById(claimId).orElseThrow(ClaimService::claimNotFound);
    }

    /** Drafts belong to intake: to anyone else they do not exist yet, so the response is 404. */
    private ClaimEntity requireVisible(UUID claimId) {
        return claims.findById(claimId)
                .filter(claim -> claim.status() != ClaimStatus.DRAFT || canManage())
                .orElseThrow(ClaimService::claimNotFound);
    }

    private static boolean canManage() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getAuthorities().stream().anyMatch(authority -> CLAIM_MANAGE.equals(authority.getAuthority()));
    }

    private static DocumentOwner ownerOf(ClaimEntity claim) {
        return new DocumentOwner(DocumentOwner.Type.CLAIM, claim.id(), stream(claim.id()));
    }

    private static String stream(UUID claimId) {
        return "claim:" + claimId;
    }

    private static ApiException claimNotFound() {
        return ApiException.notFound("Claim not found.");
    }
}
