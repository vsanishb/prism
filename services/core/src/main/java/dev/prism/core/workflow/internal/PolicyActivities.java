package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;
import java.util.List;

/** Database work for policy ingestion. Every method is safe to repeat. */
@ActivityInterface
public interface PolicyActivities {

    boolean beginIngestion(String documentId);

    /** Page ranges as "first-last". */
    List<String> pendingBatches(String documentId);

    void recordBatchFailed(String documentId, String range, String code);

    void assemble(String documentId);

    void recordAssemblyFailed(String documentId, String code);
}
