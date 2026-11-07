package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** Calls to the AI service, on their own queue so that their concurrency can be limited separately. */
@ActivityInterface
public interface ExtractionActivities {

    void extract(String claimId, String documentId);
}
