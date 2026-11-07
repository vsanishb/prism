package dev.prism.core.policyknowledge.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyKnowledgeLifecycle;
import dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState;
import dev.prism.core.policyknowledge.internal.KnowledgeView.Blocker;
import dev.prism.core.policyknowledge.internal.KnowledgeView.ValueView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Review, correction, and publication of a policy version's knowledge (D-030). */
@Service
class PolicyKnowledgeService {

    private static final Pattern DECIMAL = Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
    private static final Pattern WHOLE = Pattern.compile("\\d{1,4}");

    private final PolicyDirectory policies;
    private final PolicyKnowledgeLifecycle lifecycle;
    private final DocumentService documents;
    private final KnowledgeReader reader;
    private final AuditRecorder audit;
    private final Outbox outbox;

    PolicyKnowledgeService(
            PolicyDirectory policies,
            PolicyKnowledgeLifecycle lifecycle,
            DocumentService documents,
            KnowledgeReader reader,
            AuditRecorder audit,
            Outbox outbox) {
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.documents = documents;
        this.reader = reader;
        this.audit = audit;
        this.outbox = outbox;
    }

    record ReviewRequest(String decision, String correctedValue, String note) {}

    @PreAuthorize("hasAuthority('POLICY_READ')")
    @Transactional(readOnly = true)
    public KnowledgeView view(UUID policyId, UUID versionId) {
        VersionState version = requireVersion(policyId, versionId);
        Optional<DocumentView> wording = wording(versionId);
        KnowledgeView view = reader.read(version, wording.orElse(null));
        return view.withBlockers(blockers(view, wording.orElse(null), null));
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void review(UUID policyId, UUID versionId, UUID valueId, ReviewRequest request) {
        VersionState version = requireVersion(policyId, versionId);
        requireStatus(version, "IN_REVIEW", "Values can be reviewed only while the version is in review.");
        ValueView value = reader.value(versionId, valueId)
                .orElseThrow(() -> ApiException.notFound("Value not found."));
        boolean corrected = "CORRECTED".equals(request.decision());
        if (!corrected && !"CONFIRMED".equals(request.decision())) {
            throw ApiException.invalid("invalid_decision", "Choose to confirm or correct the value.");
        }
        String note = request.note() == null ? null : request.note().strip();
        String correction = null;
        if (corrected) {
            correction = normalizeCorrection(value.kind(), request.correctedValue());
            if (note == null || note.isEmpty()) {
                throw ApiException.invalid("note_required", "Explain the correction, so that the next reviewer can follow it.");
            }
        } else if (!"PRESENT".equals(value.state()) && value.value() == null) {
            throw ApiException.invalid("nothing_to_confirm", "This value could not be read. Enter the correct value instead.");
        }
        reader.insertReview(valueId, request.decision(), correction, note, CurrentActor.require().userId());
        audit.record(AuditEntry.of(
                        "policy:" + policyId,
                        corrected ? AuditAction.POLICY_VALUE_CORRECTED : AuditAction.POLICY_VALUE_CONFIRMED,
                        AuditOutcome.SUCCESS)
                .target("POLICY_VERSION", versionId)
                .detail(AuditDetail.VALUE_ID, valueId));
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void publish(UUID policyId, UUID versionId) {
        VersionState version = requireVersion(policyId, versionId);
        Optional<DocumentView> wording = wording(versionId);
        UUID actor = CurrentActor.require().userId();
        List<Blocker> blockers = blockers(reader.read(version, wording.orElse(null)), wording.orElse(null), actor);
        if (!blockers.isEmpty()) {
            throw ApiException.conflict("not_ready_to_publish", blockers.getFirst().message());
        }
        lifecycle.publish(versionId, actor);
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void reject(UUID policyId, UUID versionId, String reason) {
        requireVersion(policyId, versionId);
        if (reason == null || reason.isBlank()) {
            throw ApiException.invalid("reason_required", "Give a reason for rejecting this version.");
        }
        lifecycle.reject(versionId, reason.strip());
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void retry(UUID policyId, UUID versionId) {
        VersionState version = requireVersion(policyId, versionId);
        requireStatus(version, "PROCESSING", "Only a version still being read can be retried.");
        KnowledgeView view = reader.read(version, wording(versionId).orElse(null));
        if (view.batches().stream().noneMatch(batch -> "FAILED".equals(batch.status())) || view.documentId() == null) {
            throw ApiException.conflict("nothing_to_retry", "No part of this wording is waiting for a retry.");
        }
        audit.record(AuditEntry.of("policy:" + policyId, AuditAction.POLICY_INGESTION_RETRY_REQUESTED, AuditOutcome.SUCCESS)
                .target("POLICY_VERSION", versionId));
        outbox.append(Outbox.Event.POLICY_INGESTION_RETRY_REQUESTED, view.documentId());
    }

    /**
     * Why the version cannot be published yet. The publisher is checked only when known, so that the
     * screen can list the rest for anyone reading it.
     */
    private List<Blocker> blockers(KnowledgeView view, DocumentView wording, UUID publisher) {
        List<Blocker> blockers = new ArrayList<>();
        if (!"IN_REVIEW".equals(view.knowledgeStatus())) {
            blockers.add(new Blocker("NOT_IN_REVIEW", "Only a version in review can be published."));
            return blockers;
        }
        long unreviewed = view.provisions().stream()
                .flatMap(provision -> provision.values().stream())
                .filter(value -> value.critical() && value.review() == null)
                .count();
        if (unreviewed > 0) {
            blockers.add(new Blocker(
                    "VALUES_NOT_REVIEWED", unreviewed + " critical " + (unreviewed == 1 ? "value has" : "values have")
                            + " not been confirmed or corrected."));
        }
        if (view.findings().stream().anyMatch(finding -> "PAGES_NOT_READ".equals(finding.code()))) {
            blockers.add(new Blocker("PAGES_NOT_READ", "Some pages of the wording were not read."));
        }
        long breaking = view.provisions().stream()
                .flatMap(provision -> provision.values().stream()
                        .filter(value -> value.critical() && effectiveViolation(provision, value, view.sumInsured()).isPresent()))
                .count();
        if (breaking > 0) {
            blockers.add(new Blocker(
                    "VALUES_BREAK_RULES", breaking + (breaking == 1 ? " value breaks" : " values break")
                            + " a check against the policy register, even after review."));
        }
        if (publisher != null && wording != null && publisher.equals(wording.uploadedBy())) {
            blockers.add(new Blocker(
                    "SAME_PERSON", "The person who uploaded the wording cannot publish it. Ask another policy manager."));
        }
        return blockers;
    }

    /** The rule is checked against the value as reviewed: a correction must also pass it. */
    private static Optional<PolicyValidator.Rule> effectiveViolation(
            KnowledgeView.ProvisionView provision, ValueView value, BigDecimal sumInsured) {
        String figure = value.review() != null && value.review().correctedValue() != null
                ? value.review().correctedValue()
                : value.value();
        if (figure == null || "TEXT".equals(value.kind())) {
            return Optional.empty();
        }
        return PolicyValidator.violation(
                provision.type(), provision.title(), value.name(), value.kind(), new BigDecimal(figure), sumInsured);
    }

    private static String normalizeCorrection(String kind, String raw) {
        String value = raw == null ? "" : raw.strip().replace(",", "");
        switch (kind) {
            case "AMOUNT", "PERCENTAGE" -> {
                if (!DECIMAL.matcher(value).matches()) {
                    throw ApiException.invalid("invalid_value", "Enter a number, with at most two decimal places.");
                }
                return new BigDecimal(value).setScale(2).toPlainString();
            }
            case "DURATION" -> {
                if (!WHOLE.matcher(value).matches()) {
                    throw ApiException.invalid("invalid_value", "Enter a whole number in the same unit.");
                }
                return String.valueOf(Integer.parseInt(value));
            }
            default -> {
                if (value.isEmpty()) {
                    throw ApiException.invalid("invalid_value", "Enter the value as the wording states it.");
                }
                return raw.strip();
            }
        }
    }

    private VersionState requireVersion(UUID policyId, UUID versionId) {
        VersionState version = lifecycle.state(versionId);
        if (!version.policyId().equals(policyId) || policies.findPolicy(policyId).isEmpty()) {
            throw ApiException.notFound("Policy version not found.");
        }
        return version;
    }

    private static void requireStatus(VersionState version, String status, String message) {
        if (!status.equals(version.knowledgeStatus())) {
            throw ApiException.conflict("wrong_status", message);
        }
    }

    private Optional<DocumentView> wording(UUID versionId) {
        return documents.listFor(DocumentOwner.Type.POLICY_VERSION, versionId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .findFirst();
    }
}
