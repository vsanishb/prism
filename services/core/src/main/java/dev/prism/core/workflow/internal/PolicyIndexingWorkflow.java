package dev.prism.core.workflow.internal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One run per published version. Carries the version ID only. */
@WorkflowInterface
public interface PolicyIndexingWorkflow {

    @WorkflowMethod
    void index(String versionId);

    @SignalMethod
    void retryRequested();
}
