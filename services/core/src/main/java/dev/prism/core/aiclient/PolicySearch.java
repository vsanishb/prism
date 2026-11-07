package dev.prism.core.aiclient;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import java.util.List;
import java.util.UUID;

/** The AI service's search index contract. Field names match its JSON. */
public final class PolicySearch {

    private PolicySearch() {}

    public record IndexRequest(UUID versionId, UUID policyId, List<Provision> provisions, List<Relation> relations) {}

    /** A published provision. The ID is core's, so that results lead back to review and source. */
    public record Provision(
            UUID id, String key, String type, String title, String text, int pageStart, int pageEnd, String parentKey) {}

    public record Relation(UUID fromProvisionId, UUID toProvisionId, String relation) {}

    public record IndexResult(
            UUID versionId, int chunkCount, String embeddingModel, String pipelineVersion, List<ModelRun> runs) {}

    public record SearchRequest(UUID versionId, String query, List<String> types, int limit) {}

    public record SearchResult(
            UUID versionId,
            List<String> degraded,
            String embeddingModel,
            String rerankModel,
            List<Result> results,
            List<StageHit> semantic,
            List<StageHit> lexical,
            List<RelatedProvision> related,
            List<ModelRun> runs) {}

    public record Result(
            UUID provisionId,
            UUID chunkId,
            String key,
            String type,
            String title,
            List<String> sectionPath,
            int pageStart,
            int pageEnd,
            String text,
            int part,
            int parts,
            int finalRank,
            boolean pinned,
            String exact,
            StageScore semantic,
            StageScore lexical,
            StageScore fused,
            StageScore rerank,
            List<RelatedLink> related) {}

    public record StageScore(int rank, double score) {}

    public record RelatedLink(UUID provisionId, String relation, String direction) {}

    public record StageHit(UUID provisionId, String key, String type, String title, int rank, double score) {}

    public record RelatedProvision(
            UUID provisionId, String key, String type, String title, int pageStart, int pageEnd, String text) {}
}
