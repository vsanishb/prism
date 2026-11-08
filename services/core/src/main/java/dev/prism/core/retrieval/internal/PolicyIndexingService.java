package dev.prism.core.retrieval.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.PolicySearch;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.policyknowledge.PublishedKnowledge;
import dev.prism.core.policyknowledge.PublishedKnowledge.Snapshot;
import dev.prism.core.retrieval.PolicyIndexing;
import dev.prism.core.retrieval.SearchIndexes;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Sends a published version to the AI service for indexing (D-075). No model call runs inside a transaction. */
@Service
class PolicyIndexingService implements PolicyIndexing, SearchIndexes {

    private final PublishedKnowledge knowledge;
    private final SearchIndexStore store;
    private final AiServiceClient ai;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    PolicyIndexingService(
            PublishedKnowledge knowledge,
            SearchIndexStore store,
            AiServiceClient ai,
            AuditRecorder audit,
            TransactionTemplate transactions) {
        this.knowledge = knowledge;
        this.store = store;
        this.ai = ai;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public boolean begin(UUID versionId) {
        if (knowledge.snapshot(versionId).isEmpty()) {
            return false;
        }
        store.markIndexing(versionId);
        return true;
    }

    @Override
    public void build(UUID versionId) {
        Snapshot snapshot = knowledge.snapshot(versionId)
                .orElseThrow(() -> new Failure("version_not_published", false, List.of()));
        if (store.find(versionId).filter(index -> "READY".equals(index.status())).isPresent()) {
            return;
        }
        PolicySearch.IndexResult result;
        try {
            result = ai.indexPolicyVersion(request(snapshot));
        } catch (Failure failure) {
            store.saveRuns(versionId, failure.runs());
            throw failure;
        }
        transactions.executeWithoutResult(status -> {
            store.saveRuns(versionId, result.runs());
            store.markReady(versionId, result.chunkCount(), result.embeddingModel());
            audit.record(AuditEntry.of("policy:" + snapshot.policyId(), AuditAction.POLICY_SEARCH_INDEX_BUILT, AuditOutcome.SUCCESS)
                    .target("POLICY_VERSION", versionId)
                    .detail(AuditDetail.CHUNK_COUNT, result.chunkCount())
                    .detail(AuditDetail.PIPELINE_VERSION, result.pipelineVersion()));
        });
    }

    @Override
    public boolean ready(UUID versionId) {
        return store.find(versionId).filter(index -> "READY".equals(index.status())).isPresent();
    }

    @Override
    public void recordFailed(UUID versionId, String code) {
        store.markFailed(versionId, code);
    }

    private static PolicySearch.IndexRequest request(Snapshot snapshot) {
        return new PolicySearch.IndexRequest(
                snapshot.versionId(),
                snapshot.policyId(),
                snapshot.provisions().stream()
                        .map(provision -> new PolicySearch.Provision(
                                provision.id(),
                                provision.key(),
                                provision.type(),
                                provision.title(),
                                provision.text(),
                                provision.pageStart(),
                                provision.pageEnd(),
                                provision.parentKey()))
                        .toList(),
                snapshot.relations().stream()
                        .map(relation -> new PolicySearch.Relation(
                                relation.fromProvisionId(), relation.toProvisionId(), relation.relation()))
                        .toList());
    }
}
