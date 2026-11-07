package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** Model calls for policy pages, on the AI queue with its concurrency limit. */
@ActivityInterface
public interface PolicyExtractionActivities {

    void extractPolicyBatch(String documentId, String range);
}
