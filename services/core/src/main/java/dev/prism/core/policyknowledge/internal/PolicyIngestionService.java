package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentService.OwnedDocument;
import dev.prism.core.document.DocumentView;
import dev.prism.core.policy.PolicyKnowledgeLifecycle;
import dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState;
import dev.prism.core.policyknowledge.PolicyIngestion;
import dev.prism.core.policyknowledge.internal.KnowledgeStore.Ingestion;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs as the system, from workflow activities. No transaction is held during a model call. */
@Service
class PolicyIngestionService implements PolicyIngestion {

    private final DocumentService documents;
    private final AiServiceClient ai;
    private final KnowledgeStore store;
    private final PolicyKnowledgeLifecycle versions;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    PolicyIngestionService(
            DocumentService documents,
            AiServiceClient ai,
            KnowledgeStore store,
            PolicyKnowledgeLifecycle versions,
            AuditRecorder audit,
            TransactionTemplate transactions) {
        this.documents = documents;
        this.ai = ai;
        this.store = store;
        this.versions = versions;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public boolean begin(UUID documentId) {
        Optional<OwnedDocument> wording = currentWording(documentId);
        wording.ifPresent(found -> store.createIngestion(found.ownerId(), documentId, found.document().pageCount()));
        return wording.isPresent();
    }

    @Override
    public List<PageRange> pendingBatches(UUID documentId) {
        if (currentWording(documentId).isEmpty()) {
            return List.of();
        }
        Ingestion ingestion = requireIngestion(documentId);
        Set<Integer> done = Set.copyOf(store.extractedBatchStarts(ingestion.id()));
        return PolicyAssembler.batches(ingestion.pageCount()).stream()
                .filter(range -> !done.contains(range[0]))
                .map(range -> new PageRange(range[0], range[1]))
                .toList();
    }

    @Override
    public void extractBatch(UUID documentId, PageRange range) {
        Ingestion ingestion = requireIngestion(documentId);
        if (currentWording(documentId).isEmpty() || store.extractedBatchStarts(ingestion.id()).contains(range.first())) {
            return;
        }
        store.recordBatch(ingestion.id(), range.first(), range.last(), "PROCESSING", null);
        DocumentService.StoredContent content = documents.readForProcessing(
                DocumentOwner.Type.POLICY_VERSION, ingestion.versionId(), documentId);
        PolicyExtraction.Result result;
        try {
            result = ai.extractPolicyPages(new PolicyExtraction.Request(
                    documentId, content.document().mediaType(), range.first(), range.last(), content.content()));
        } catch (Failure failure) {
            // Failed attempts were still made, and paid for.
            store.saveRuns(ingestion.id(), documentId, failure.runs());
            throw failure;
        }
        transactions.executeWithoutResult(status -> {
            store.saveBatch(ingestion.id(), result, range.first(), range.last());
            store.saveRuns(ingestion.id(), documentId, result.runs());
            store.recordBatch(ingestion.id(), range.first(), range.last(), "COMPLETED", null);
            VersionState version = versions.state(ingestion.versionId());
            audit.record(AuditEntry.of("policy:" + version.policyId(), AuditAction.POLICY_PAGES_EXTRACTED, AuditOutcome.SUCCESS)
                    .target("DOCUMENT", documentId)
                    .detail(AuditDetail.PAGES, range));
        });
    }

    @Override
    public void recordBatchFailed(UUID documentId, PageRange range, String code) {
        store.recordBatch(requireIngestion(documentId).id(), range.first(), range.last(), "FAILED", code);
    }

    @Override
    public void recordAssemblyFailed(UUID documentId, String code) {
        recordBatchFailed(documentId, PageRange.ASSEMBLY, code);
    }

    @Override
    public void assemble(UUID documentId) {
        Ingestion ingestion = requireIngestion(documentId);
        if (currentWording(documentId).isEmpty() || store.alreadyAssembled(ingestion.id())) {
            return;
        }
        VersionState version = versions.state(ingestion.versionId());
        List<PolicyExtraction.Result> batches = store.batches(ingestion.id());
        List<Assembly.Provision> provisions = PolicyAssembler.provisions(batches);
        List<Assembly.Relation> relations = PolicyAssembler.relations(provisions);
        List<int[]> read = batches.stream().map(batch -> new int[] {batch.firstPage(), batch.lastPage()}).toList();
        List<Assembly.Finding> findings =
                PolicyValidator.validate(provisions, relations, ingestion.pageCount(), read, version.sumInsured());
        transactions.executeWithoutResult(status -> {
            store.saveAssembly(ingestion.id(), new Assembly(provisions, relations, findings));
            versions.readyForReview(ingestion.versionId());
        });
    }

    /** Only the version's active wording is worth reading: a replaced one is history. */
    private Optional<OwnedDocument> currentWording(UUID documentId) {
        return documents.find(documentId)
                .filter(found -> found.ownerType() == DocumentOwner.Type.POLICY_VERSION
                        && found.document().status() == DocumentView.Status.ACTIVE);
    }

    private Ingestion requireIngestion(UUID documentId) {
        return store.ingestion(documentId).orElseThrow(() -> new IllegalStateException("No ingestion for " + documentId));
    }
}
