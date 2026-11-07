package dev.prism.core.intelligence;

import java.util.List;
import java.util.UUID;

/** Turns a claim's documents into structured, source-linked facts. Used by the claim workflow. */
public interface ClaimIntelligence {

    /** Active documents with no stored result for the current pipeline. */
    List<UUID> documentsToExtract(UUID claimId);

    /**
     * Extracts one document and stores the result. Idempotent, so it is safe for a retried activity.
     *
     * @throws dev.prism.core.aiclient.DocumentExtraction.Failure if the AI service could not finish
     */
    void extract(UUID claimId, UUID documentId);

    /** Merges all current extractions into a new set of facts and findings. */
    Consolidation consolidate(UUID claimId);

    /**
     * @param complete whether every required document is present
     * @param needsAttention whether any finding should be looked at by a person
     */
    record Consolidation(int sequence, boolean complete, boolean needsAttention) {}
}
