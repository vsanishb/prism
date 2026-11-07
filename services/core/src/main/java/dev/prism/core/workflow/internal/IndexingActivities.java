package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** Database work for search indexing. Every method is safe to repeat. */
@ActivityInterface
public interface IndexingActivities {

    boolean beginIndexing(String versionId);

    void recordIndexingFailed(String versionId, String code);
}
