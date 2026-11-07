package dev.prism.core.retrieval.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.PolicySearch;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.platform.web.ApiException;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyKnowledgeLifecycle;
import dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState;
import dev.prism.core.policyknowledge.PublishedKnowledge;
import dev.prism.core.policyknowledge.PublishedKnowledge.Provision;
import dev.prism.core.policyknowledge.PublishedKnowledge.Snapshot;
import dev.prism.core.retrieval.internal.SearchIndexStore.IndexStatus;
import dev.prism.core.retrieval.internal.SearchView.IndexView;
import dev.prism.core.retrieval.internal.SearchView.RelatedView;
import dev.prism.core.retrieval.internal.SearchView.ResultView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Search over one published version, for people. The Policy Agent will search through the same AI endpoint. */
@Service
class PolicySearchService {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchService.class);
    static final Set<String> PROVISION_TYPES =
            Set.of("SCHEDULE", "DEFINITION", "BENEFIT", "WAITING_PERIOD", "EXCLUSION", "PROCEDURE", "CLAUSE");
    private static final int RESULTS = 10;

    private final PolicyDirectory policies;
    private final PolicyKnowledgeLifecycle lifecycle;
    private final PublishedKnowledge knowledge;
    private final SearchIndexStore store;
    private final AiServiceClient ai;
    private final AuditRecorder audit;
    private final Outbox outbox;

    PolicySearchService(
            PolicyDirectory policies,
            PolicyKnowledgeLifecycle lifecycle,
            PublishedKnowledge knowledge,
            SearchIndexStore store,
            AiServiceClient ai,
            AuditRecorder audit,
            Outbox outbox) {
        this.policies = policies;
        this.lifecycle = lifecycle;
        this.knowledge = knowledge;
        this.store = store;
        this.ai = ai;
        this.audit = audit;
        this.outbox = outbox;
    }

    @PreAuthorize("hasAuthority('POLICY_READ')")
    @Transactional(readOnly = true)
    public IndexView index(UUID policyId, UUID versionId) {
        VersionState version = requireVersion(policyId, versionId);
        if (!"PUBLISHED".equals(version.knowledgeStatus())) {
            return new IndexView("NOT_PUBLISHED", null, null, null, null, null);
        }
        return store.find(versionId)
                .map(PolicySearchService::view)
                // Published, and the indexing event not yet picked up.
                .orElse(new IndexView("QUEUED", null, null, null, null, null));
    }

    /**
     * Not transactional: model usage is recorded even when the search fails, and no connection is
     * held while the AI service works.
     */
    @PreAuthorize("hasAuthority('POLICY_READ')")
    public SearchView search(UUID policyId, UUID versionId, String rawQuery, List<String> rawTypes) {
        VersionState version = requireVersion(policyId, versionId);
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.length() < 2) {
            throw ApiException.invalid("query_too_short", "Enter at least two characters.");
        }
        List<String> types = rawTypes == null ? List.of() : rawTypes.stream().distinct().toList();
        if (!PROVISION_TYPES.containsAll(types)) {
            throw ApiException.invalid("invalid_type", "Choose provision types from the list.");
        }
        if (!"PUBLISHED".equals(version.knowledgeStatus())) {
            throw ApiException.conflict("not_published", "Only a published version can be searched.");
        }
        if (store.find(versionId).filter(index -> "READY".equals(index.status())).isEmpty()) {
            throw ApiException.conflict("index_not_ready", "The search index for this version is not ready yet.");
        }
        Snapshot snapshot = knowledge.snapshot(versionId)
                .orElseThrow(() -> ApiException.conflict("not_published", "Only a published version can be searched."));

        PolicySearch.SearchResult result;
        try {
            result = ai.searchPolicyVersion(new PolicySearch.SearchRequest(versionId, query, types, RESULTS));
        } catch (Failure failure) {
            store.saveRuns(versionId, failure.runs());
            log.warn("Search failed for version {}: {}", versionId, failure.code());
            throw "index_not_found".equals(failure.code())
                    ? ApiException.conflict("index_not_ready", "The search index for this version is not ready yet.")
                    : ApiException.unavailable("search_unavailable", "Search is unavailable. Try again shortly.");
        }
        store.saveRuns(versionId, result.runs());
        return view(snapshot, query, result);
    }

    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Transactional
    public void retry(UUID policyId, UUID versionId) {
        requireVersion(policyId, versionId);
        if (store.find(versionId).filter(index -> "FAILED".equals(index.status())).isEmpty()) {
            throw ApiException.conflict("nothing_to_retry", "Only a failed index can be retried.");
        }
        audit.record(AuditEntry.of("policy:" + policyId, AuditAction.POLICY_SEARCH_INDEX_RETRY_REQUESTED, AuditOutcome.SUCCESS)
                .target("POLICY_VERSION", versionId));
        outbox.append(Outbox.Event.POLICY_INDEX_RETRY_REQUESTED, versionId);
    }

    /**
     * Joins results to their review records, for the source page and outline. Every provision must
     * belong to this version: anything else is refused, never shown (D-075).
     */
    private static SearchView view(Snapshot snapshot, String query, PolicySearch.SearchResult result) {
        Map<UUID, Provision> provisions = snapshot.provisions().stream()
                .collect(Collectors.toMap(Provision::id, Function.identity()));
        boolean foreign = Stream.of(
                        result.results().stream().map(PolicySearch.Result::provisionId),
                        result.results().stream().flatMap(item -> item.related().stream()).map(PolicySearch.RelatedLink::provisionId),
                        result.semantic().stream().map(PolicySearch.StageHit::provisionId),
                        result.lexical().stream().map(PolicySearch.StageHit::provisionId),
                        result.related().stream().map(PolicySearch.RelatedProvision::provisionId))
                .flatMap(Function.identity())
                .anyMatch(id -> !provisions.containsKey(id));
        if (foreign || !snapshot.versionId().equals(result.versionId())) {
            log.error("Search for version {} returned provisions of another version", snapshot.versionId());
            throw ApiException.unavailable("search_unavailable", "Search is unavailable. Try again shortly.");
        }
        return new SearchView(
                snapshot.versionId(),
                snapshot.documentId(),
                query,
                result.degraded(),
                result.embeddingModel(),
                result.rerankModel(),
                result.results().stream().map(item -> result(item, provisions.get(item.provisionId()))).toList(),
                result.semantic(),
                result.lexical(),
                result.related().stream()
                        .map(item -> {
                            Provision source = provisions.get(item.provisionId());
                            return new RelatedView(
                                    item.provisionId(), item.key(), item.type(), item.title(), item.pageStart(), item.pageEnd(),
                                    item.text(), source.quote(), source.region());
                        })
                        .toList());
    }

    private static ResultView result(PolicySearch.Result item, Provision source) {
        return new ResultView(
                item.provisionId(),
                item.key(),
                item.type(),
                item.title(),
                item.sectionPath(),
                item.pageStart(),
                item.pageEnd(),
                item.text(),
                item.part(),
                item.parts(),
                item.finalRank(),
                item.pinned(),
                item.exact(),
                item.semantic(),
                item.lexical(),
                item.fused(),
                item.rerank(),
                source.quote(),
                source.region(),
                item.related());
    }

    private static IndexView view(IndexStatus status) {
        return new IndexView(
                status.status(),
                status.chunkCount(),
                status.embeddingModel(),
                status.failureCode(),
                status.requestedAt(),
                status.completedAt());
    }

    private VersionState requireVersion(UUID policyId, UUID versionId) {
        VersionState version = lifecycle.state(versionId);
        if (!version.policyId().equals(policyId) || policies.findPolicy(policyId).isEmpty()) {
            throw ApiException.notFound("Policy version not found.");
        }
        return version;
    }
}
