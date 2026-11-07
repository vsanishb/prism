package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.DocumentExtraction.Result;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentService.StoredContent;
import dev.prism.core.document.DocumentView;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.DocumentLineItem;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.ExtractedDocument;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Finding;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Severity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs as the system, from workflow activities. Model calls take minutes, so no database transaction
 * is held across them: results are stored in a short transaction afterwards.
 */
@Service
class DefaultClaimIntelligence implements ClaimIntelligence {

    private final ClaimDirectory claims;
    private final DocumentService documents;
    private final AiServiceClient ai;
    private final IntelligenceStore store;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    DefaultClaimIntelligence(
            ClaimDirectory claims,
            DocumentService documents,
            AiServiceClient ai,
            IntelligenceStore store,
            AuditRecorder audit,
            TransactionTemplate transactions) {
        this.claims = claims;
        this.documents = documents;
        this.ai = ai;
        this.store = store;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public List<UUID> documentsToExtract(UUID claimId) {
        Set<UUID> done = store.extractedDocuments(claimId);
        return activeDocuments(claimId).stream().map(DocumentView::id).filter(id -> !done.contains(id)).toList();
    }

    @Override
    public void extract(UUID claimId, UUID documentId) {
        ClaimRecord claim = claims.get(claimId);
        if (claim.closed() || store.extractedDocuments(claimId).contains(documentId)) {
            return;
        }
        StoredContent stored = documents.readForProcessing(DocumentOwner.Type.CLAIM, claimId, documentId);
        DocumentView view = stored.document();
        if (view.status() != DocumentView.Status.ACTIVE) {
            return;
        }
        String stream = stream(claimId);
        audit.recordIndependently(AuditEntry.of(stream, AuditAction.DOCUMENT_SENT_FOR_EXTRACTION, AuditOutcome.SUCCESS)
                .target("DOCUMENT", documentId));

        Result result;
        try {
            result = ai.extractDocument(new DocumentExtraction.Request(
                    documentId, view.documentType().name(), view.mediaType(), stored.content()));
        } catch (Failure failure) {
            recordFailure(claimId, documentId, failure);
            throw failure;
        }
        transactions.executeWithoutResult(status -> {
            store.saveExtraction(claimId, documentId, view.documentType().name(), result);
            store.saveRuns(claimId, documentId, result.runs());
            audit.record(AuditEntry.of(stream, AuditAction.DOCUMENT_EXTRACTED, AuditOutcome.SUCCESS)
                    .target("DOCUMENT", documentId)
                    .detail(AuditDetail.DOCUMENT_TYPE, result.classification().documentType())
                    .detail(AuditDetail.PIPELINE_VERSION, result.pipelineVersion()));
        });
    }

    /** Usage is kept even for a failed attempt: those calls happened, and were paid for. */
    private void recordFailure(UUID claimId, UUID documentId, Failure failure) {
        transactions.executeWithoutResult(status -> {
            store.saveRuns(claimId, documentId, failure.runs());
            if (!failure.retryable()) {
                audit.record(AuditEntry.of(stream(claimId), AuditAction.DOCUMENT_EXTRACTION_FAILED, AuditOutcome.FAILURE)
                        .target("DOCUMENT", documentId)
                        .detail(AuditDetail.REASON, failure.code()));
            }
        });
    }

    @Override
    public Consolidation consolidate(UUID claimId) {
        ClaimRecord claim = claims.get(claimId);
        List<ExtractedDocument> extracted = activeDocuments(claimId).stream()
                .flatMap(document -> store.latestExtraction(document.id())
                        .map(result -> new ExtractedDocument(document.id(), document.documentType().name(), result))
                        .stream())
                .toList();

        List<Fact> facts = FactConsolidator.facts(extracted);
        List<DocumentLineItem> lineItems = FactConsolidator.lineItems(extracted);
        List<Finding> findings = ClaimValidator.validate(claim, extracted, facts, lineItems);
        boolean complete = findings.stream()
                .noneMatch(finding -> ClaimValidator.MISSING_REQUIRED_DOCUMENT.equals(finding.code()));
        boolean needsAttention = findings.stream().anyMatch(finding -> finding.severity() != Severity.INFO);

        Optional<Integer> sequence = Optional.ofNullable(transactions.execute(status -> {
            int stored = store.saveConsolidation(claimId, complete, new ConsolidatedClaim(facts, lineItems, findings));
            audit.record(AuditEntry.of(stream(claimId), AuditAction.CLAIM_FACTS_CONSOLIDATED, AuditOutcome.SUCCESS)
                    .target("CLAIM", claimId)
                    .detail(AuditDetail.CONSOLIDATION_SEQUENCE, stored));
            return stored;
        }));
        return new Consolidation(sequence.orElseThrow(), complete, needsAttention);
    }

    private List<DocumentView> activeDocuments(UUID claimId) {
        return documents.listFor(DocumentOwner.Type.CLAIM, claimId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .toList();
    }

    private static String stream(UUID claimId) {
        return "claim:" + claimId;
    }
}
