package dev.prism.core.retrieval;

import java.util.UUID;

/** Builds a published version's search index. Used by the indexing workflow; every method is safe to repeat. */
public interface PolicyIndexing {

    /** Marks the index as being built. Returns false if the version is not published, so there is nothing to do. */
    boolean begin(UUID versionId);

    /**
     * Sends the published provisions to the AI service, which replaces the version's index.
     *
     * @throws dev.prism.core.aiclient.DocumentExtraction.Failure if the AI service could not finish
     */
    void build(UUID versionId);

    void recordFailed(UUID versionId, String code);
}
