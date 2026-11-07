package dev.prism.core.workflow.internal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One run per uploaded wording. Carries the document ID only. */
@WorkflowInterface
public interface PolicyIngestionWorkflow {

    @WorkflowMethod
    void ingest(String documentId);

    @SignalMethod
    void retryRequested();
}
