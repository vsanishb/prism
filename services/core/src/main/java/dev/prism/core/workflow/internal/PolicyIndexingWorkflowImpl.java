package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Indexes a published version for search. Orchestration only, and deterministic. A failure waits
 * for a person to ask for a retry, rather than ending, so that a published version is never left
 * unsearchable without anyone knowing.
 */
public class PolicyIndexingWorkflowImpl implements PolicyIndexingWorkflow {

    private final IndexingActivities database = Workflow.newActivityStub(
            IndexingActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimProcessingWorkflowImpl.DATABASE_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private final IndexBuildActivities model = Workflow.newActivityStub(
            IndexBuildActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimProcessingWorkflowImpl.AI_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Heartbeats.TIMEOUT)
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(3)
                            .setMaximumAttempts(4)
                            .build())
                    .build());

    private boolean retryRequested;

    @Override
    public void index(String versionId) {
        while (true) {
            retryRequested = false;
            if (!database.beginIndexing(versionId)) {
                return;
            }
            try {
                model.buildIndex(versionId);
                return;
            } catch (ActivityFailure failure) {
                database.recordIndexingFailed(versionId, failureCode(failure));
            }
            Workflow.await(() -> retryRequested);
        }
    }

    private static String failureCode(ActivityFailure failure) {
        return failure.getCause() instanceof ApplicationFailure application ? application.getType() : "timed_out";
    }

    @Override
    public void retryRequested() {
        retryRequested = true;
    }
}
