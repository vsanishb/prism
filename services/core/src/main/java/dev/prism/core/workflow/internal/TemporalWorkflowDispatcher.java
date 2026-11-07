package dev.prism.core.workflow.internal;

import dev.prism.core.platform.outbox.Outbox;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TemporalWorkflowDispatcher implements WorkflowDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkflowDispatcher.class);

    private final WorkflowClient client;
    private final Duration informationWait;

    TemporalWorkflowDispatcher(WorkflowClient client, Duration informationWait) {
        this.client = client;
        this.informationWait = informationWait;
    }

    static String workflowId(UUID claimId) {
        return "claim-" + claimId;
    }

    @Override
    public void dispatch(Outbox.Event event, UUID claimId) {
        switch (event) {
            case CLAIM_SUBMITTED -> start(claimId);
            case CLAIM_DOCUMENTS_CHANGED -> signal(claimId, ClaimProcessingWorkflow::documentsChanged);
            case CLAIM_RETRY_REQUESTED -> signal(claimId, ClaimProcessingWorkflow::retryRequested);
            case CLAIM_WITHDRAWN -> signal(claimId, ClaimProcessingWorkflow::claimWithdrawn);
        }
    }

    /** The ID comes from the claim, so that repeated delivery cannot start a second workflow. */
    private void start(UUID claimId) {
        ClaimProcessingWorkflow workflow = client.newWorkflowStub(
                ClaimProcessingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId(claimId))
                        .setTaskQueue(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build());
        try {
            WorkflowClient.start(workflow::process, claimId.toString(), informationWait.toSeconds());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.debug("Workflow already started for claim {}", claimId);
        }
    }

    private void signal(UUID claimId, java.util.function.Consumer<ClaimProcessingWorkflow> send) {
        ClaimProcessingWorkflow workflow = client.newWorkflowStub(ClaimProcessingWorkflow.class, workflowId(claimId));
        try {
            send.accept(workflow);
        } catch (WorkflowNotFoundException finished) {
            // A closed claim's workflow has ended, and nothing is left to tell.
            log.info("No running workflow for claim {}", claimId);
        }
    }
}
