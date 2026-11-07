package dev.prism.core.workflow.internal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Inputs and signals carry IDs and numbers only, so no claim content reaches workflow history. */
@WorkflowInterface
public interface ClaimProcessingWorkflow {

    @WorkflowMethod
    void process(String claimId, long informationWaitSeconds);

    @SignalMethod
    void documentsChanged();

    @SignalMethod
    void retryRequested();

    @SignalMethod
    void claimWithdrawn();
}
