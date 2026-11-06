package dev.prism.core.policy.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.document.DocumentAccessGuard;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentType;
import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.platform.web.Page;
import dev.prism.core.policy.PolicyDirectory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class PolicyService implements PolicyDirectory, DocumentAccessGuard {

    private static final UUID FIRST_PAGE = new UUID(0, 0);

    private final PolicyRepository policies;
    private final PolicyVersionRepository versions;
    private final DocumentService documents;
    private final AuditRecorder audit;
    private final String defaultCurrency;

    PolicyService(
            PolicyRepository policies,
            PolicyVersionRepository versions,
            DocumentService documents,
            AuditRecorder audit,
            PrismProperties properties) {
        this.policies = policies;
        this.versions = versions;
        this.documents = documents;
        this.audit = audit;
        this.defaultCurrency = properties.defaultCurrency();
    }

    record PolicySummary(UUID id, String policyNumber, String productName, String policyholderName, Instant createdAt) {
        static PolicySummary of(PolicyEntity policy) {
            return new PolicySummary(
                    policy.id(), policy.policyNumber(), policy.productName(), policy.policyholderName(), policy.createdAt());
        }
    }

    record VersionDetail(VersionRef version, List<DocumentView> documents) {}

    record PolicyDetail(PolicySummary policy, List<VersionDetail> versions) {}

    @PreAuthorize("hasAuthority('POLICY_READ')")
    @Transactional(readOnly = true)
    public Page<PolicySummary> list(String search, UUID after, int limit) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<PolicyEntity> rows = policies.page(after == null ? FIRST_PAGE : after, term, Limit.of(limit + 1));
        boolean hasMore = rows.size() > limit;
        List<PolicyEntity> items = hasMore ? rows.subList(0, limit) : rows;
        return new Page<>(items.stream().map(PolicySummary::of).toList(), hasMore ? items.getLast().id().toString() : null);
    }

    @PreAuthorize("hasAuthority('POLICY_READ')")
    @Transactional(readOnly = true)
    public PolicyDetail detail(UUID policyId) {
        PolicyEntity policy = policies.findById(policyId).orElseThrow(PolicyService::policyNotFound);
        List<VersionDetail> details = versions.findByPolicyIdOrderByVersionNumberDesc(policyId).stream()
                .map(version -> new VersionDetail(
                        version.toRef(), documents.listFor(DocumentOwner.Type.POLICY_VERSION, version.id())))
                .toList();
        return new PolicyDetail(PolicySummary.of(policy), details);
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public PolicySummary create(String policyNumber, String productName, String policyholderName) {
        String number = policyNumber.trim().toUpperCase(Locale.ROOT);
        if (policies.existsByPolicyNumber(number)) {
            throw ApiException.conflict("policy_number_in_use", "A policy with this number already exists.");
        }
        PolicyEntity policy = policies.save(
                new PolicyEntity(number, productName.trim(), policyholderName.trim(), CurrentActor.require().userId()));
        audit.record(AuditEntry.of(stream(policy.id()), AuditAction.POLICY_CREATED, AuditOutcome.SUCCESS)
                .target("POLICY", policy.id()));
        return PolicySummary.of(policy);
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public VersionRef addVersion(UUID policyId, LocalDate from, LocalDate to, BigDecimal sumInsured) {
        policies.lockById(policyId).orElseThrow(PolicyService::policyNotFound);
        if (to.isBefore(from)) {
            throw ApiException.invalid("invalid_period", "The end date must not be before the start date.");
        }
        PolicyVersionEntity version = new PolicyVersionEntity(
                policyId, versions.highestVersionNumber(policyId) + 1, from, to, sumInsured, defaultCurrency,
                CurrentActor.require().userId());
        try {
            versions.saveAndFlush(version);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("overlapping_period", "This period overlaps an existing version of the policy.");
        }
        audit.record(AuditEntry.of(stream(policyId), AuditAction.POLICY_VERSION_CREATED, AuditOutcome.SUCCESS)
                .target("POLICY_VERSION", version.id())
                .detail(AuditDetail.VERSION_NUMBER, version.versionNumber()));
        return version.toRef();
    }

    /** A version has one wording: uploading again supersedes the earlier file. */
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public DocumentView attachWording(UUID policyId, UUID versionId, MultipartFile file) {
        DocumentOwner owner = ownerOf(requireVersion(policyId, versionId));
        Optional<DocumentView> current = activeWording(versionId);
        return current.isPresent()
                ? documents.replace(owner, current.get().id(), file)
                : documents.store(owner, DocumentType.POLICY_WORDING, file);
    }

    /** For the synthetic seeder, which reads wordings from disk. */
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void attachWording(UUID policyId, UUID versionId, Path file) {
        documents.store(ownerOf(requireVersion(policyId, versionId)), DocumentType.POLICY_WORDING, file);
    }

    public boolean exists(String policyNumber) {
        return policies.existsByPolicyNumber(policyNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyRef> findPolicy(UUID policyId) {
        return policies.findById(policyId).map(policy -> new PolicyRef(
                policy.id(), policy.policyNumber(), policy.productName(), policy.policyholderName()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VersionRef> versionCovering(UUID policyId, LocalDate date) {
        return versions.findCovering(policyId, date).map(PolicyVersionEntity::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VersionRef> findVersion(UUID versionId) {
        return versions.findById(versionId).map(PolicyVersionEntity::toRef);
    }

    @Override
    public DocumentOwner.Type ownerType() {
        return DocumentOwner.Type.POLICY_VERSION;
    }

    @Override
    @PreAuthorize("hasAuthority('POLICY_READ')")
    @Transactional(readOnly = true)
    public DocumentOwner requireReadable(UUID ownerId) {
        return ownerOf(versions.findById(ownerId).orElseThrow(() -> ApiException.notFound("Document not found.")));
    }

    private Optional<DocumentView> activeWording(UUID versionId) {
        return documents.listFor(DocumentOwner.Type.POLICY_VERSION, versionId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .findFirst();
    }

    private PolicyVersionEntity requireVersion(UUID policyId, UUID versionId) {
        return versions.findById(versionId)
                .filter(version -> version.policyId().equals(policyId))
                .orElseThrow(() -> ApiException.notFound("Policy version not found."));
    }

    private static DocumentOwner ownerOf(PolicyVersionEntity version) {
        return new DocumentOwner(DocumentOwner.Type.POLICY_VERSION, version.id(), stream(version.policyId()));
    }

    private static String stream(UUID policyId) {
        return "policy:" + policyId;
    }

    private static ApiException policyNotFound() {
        return ApiException.notFound("Policy not found.");
    }
}
