package dev.prism.core.retrieval.internal;

import dev.prism.core.aiclient.PolicySearch.RelatedLink;
import dev.prism.core.aiclient.PolicySearch.StageHit;
import dev.prism.core.aiclient.PolicySearch.StageScore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A search over one version, with every stage's ranking, for the retrieval explorer. */
record SearchView(
        UUID versionId,
        UUID documentId,
        String query,
        List<String> degraded,
        String embeddingModel,
        String rerankModel,
        List<ResultView> results,
        List<StageHit> semantic,
        List<StageHit> lexical,
        List<RelatedView> related) {

    record ResultView(
            UUID provisionId,
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
            String quote,
            List<BigDecimal> region,
            List<RelatedLink> related) {}

    record RelatedView(
            UUID provisionId,
            String key,
            String type,
            String title,
            int pageStart,
            int pageEnd,
            String text,
            String quote,
            List<BigDecimal> region) {}

    /** NOT_PUBLISHED and QUEUED come from core's state. INDEXING, READY, and FAILED from the index record. */
    record IndexView(
            String status,
            Integer chunkCount,
            String embeddingModel,
            String failureCode,
            Instant requestedAt,
            Instant completedAt) {}
}
