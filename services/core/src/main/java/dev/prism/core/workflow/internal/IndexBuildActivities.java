package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** The model calls of indexing, on the AI queue with its concurrency limit. */
@ActivityInterface
public interface IndexBuildActivities {

    void buildIndex(String versionId);
}
