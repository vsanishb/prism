package dev.prism.core.retrieval.internal;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Core's record of each version's search index, and the model usage of indexing and search. */
@Repository
class SearchIndexStore {

    private final JdbcClient jdbc;

    SearchIndexStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record IndexStatus(
            String status, Integer chunkCount, String embeddingModel, String failureCode, Instant requestedAt, Instant completedAt) {}

    Optional<IndexStatus> find(UUID versionId) {
        return jdbc.sql("SELECT * FROM core.policy_search_indexes WHERE version_id = ?")
                .param(versionId)
                .query((row, index) -> new IndexStatus(
                        row.getString("status"),
                        (Integer) row.getObject("chunk_count"),
                        row.getString("embedding_model"),
                        row.getString("failure_code"),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        Optional.ofNullable(row.getObject("completed_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();
    }

    /** A rebuild starts again from INDEXING, keeping the row: indexes are never deleted. */
    void markIndexing(UUID versionId) {
        jdbc.sql("""
                        INSERT INTO core.policy_search_indexes (version_id, status) VALUES (?, 'INDEXING')
                        ON CONFLICT (version_id) DO UPDATE SET status = 'INDEXING', failure_code = NULL,
                            requested_at = now(), completed_at = NULL, chunk_count = NULL
                        WHERE core.policy_search_indexes.status <> 'READY'
                        """)
                .param(versionId)
                .update();
    }

    void markReady(UUID versionId, int chunkCount, String embeddingModel) {
        jdbc.sql("""
                        UPDATE core.policy_search_indexes
                        SET status = 'READY', chunk_count = ?, embedding_model = ?, failure_code = NULL, completed_at = now()
                        WHERE version_id = ?
                        """)
                .params(chunkCount, embeddingModel, versionId)
                .update();
    }

    /** Does nothing once the index is ready: a late failure report must not hide a working index. */
    void markFailed(UUID versionId, String code) {
        jdbc.sql("""
                        UPDATE core.policy_search_indexes SET status = 'FAILED', failure_code = ?, completed_at = NULL
                        WHERE version_id = ? AND status <> 'READY'
                        """)
                .params(code, versionId)
                .update();
    }

    void saveRuns(UUID versionId, List<ModelRun> runs) {
        for (ModelRun run : runs) {
            jdbc.sql("""
                            INSERT INTO core.ai_runs (version_id, task, model, provider, prompt_version, attempt,
                                outcome, input_tokens, output_tokens, cost_usd, latency_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            versionId, run.task(), run.model(), run.provider(), run.promptVersion(), run.attempt(),
                            run.outcome(), run.inputTokens(), run.outputTokens(), run.costUsd(), run.latencyMs())
                    .update();
        }
    }
}
