package dev.prism.core.workflow.internal;

import dev.prism.core.workflow.WorkflowHealth;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.concurrent.TimeUnit;

class TemporalWorkflowHealth implements WorkflowHealth {

    private static final long TIMEOUT_SECONDS = 3;

    private final WorkflowServiceStubs stubs;

    TemporalWorkflowHealth(WorkflowServiceStubs stubs) {
        this.stubs = stubs;
    }

    @Override
    public boolean reachable() {
        try {
            stubs.blockingStub()
                    .withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
            return true;
        } catch (StatusRuntimeException exception) {
            return false;
        }
    }
}
