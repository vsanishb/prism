package dev.prism.core.policyknowledge;

import java.util.List;
import java.util.UUID;

/** Turns a policy wording into structured knowledge. Used by the ingestion workflow. */
public interface PolicyIngestion {

    /** Registers the ingestion. Returns false if the wording is no longer current, so there is nothing to do. */
    boolean begin(UUID documentId);

    /** Page ranges not yet read, in order. Ranges overlap by one page so that cut clauses join up. */
    List<PageRange> pendingBatches(UUID documentId);

    /**
     * Reads and stores one batch. Idempotent.
     *
     * @throws dev.prism.core.aiclient.DocumentExtraction.Failure if the AI service could not finish
     */
    void extractBatch(UUID documentId, PageRange range);

    void recordBatchFailed(UUID documentId, PageRange range, String code);

    void recordAssemblyFailed(UUID documentId, String code);

    /** Joins the batches into provisions, validates them, and opens the version for review. */
    void assemble(UUID documentId);

    record PageRange(int first, int last) {

        /** Stands for assembly in progress records. */
        public static final PageRange ASSEMBLY = new PageRange(0, 0);

        public static PageRange parse(String text) {
            String[] parts = text.split("-");
            return new PageRange(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        @Override
        public String toString() {
            return first + "-" + last;
        }
    }
}
