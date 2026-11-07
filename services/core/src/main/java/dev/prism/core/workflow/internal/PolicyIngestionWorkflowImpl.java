package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a wording in page batches, then assembles and validates it. Orchestration only, and
 * deterministic. A failed batch waits for a retry instead of ending, so no wording is lost.
 */
public class PolicyIngestionWorkflowImpl implements PolicyIngestionWorkflow {

    private static final int MAX_PARALLEL_BATCHES = 3;

    private final PolicyActivities durable = Workflow.newActivityStub(
            PolicyActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimProcessingWorkflowImpl.DATABASE_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    private final PolicyActivities bounded = Workflow.newActivityStub(
            PolicyActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(ClaimProcessingWorkflowImpl.DATABASE_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final PolicyExtractionActivities extraction = Workflow.newActivityStub(
            PolicyExtractionActivities.class,
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
    public void ingest(String documentId) {
        if (!durable.beginIngestion(documentId)) {
            return;
        }
        while (true) {
            retryRequested = false;
            if (extractBatches(documentId) && assemble(documentId)) {
                return;
            }
            Workflow.await(() -> retryRequested);
        }
    }

    private boolean extractBatches(String documentId) {
        List<String> pending = durable.pendingBatches(documentId);
        boolean succeeded = true;
        for (int start = 0; start < pending.size(); start += MAX_PARALLEL_BATCHES) {
            List<String> group = pending.subList(start, Math.min(start + MAX_PARALLEL_BATCHES, pending.size()));
            List<Promise<Void>> running = new ArrayList<>();
            for (String range : group) {
                running.add(Async.procedure(extraction::extractPolicyBatch, documentId, range));
            }
            for (int index = 0; index < group.size(); index++) {
                try {
                    running.get(index).get();
                } catch (ActivityFailure failure) {
                    succeeded = false;
                    durable.recordBatchFailed(documentId, group.get(index), failureCode(failure));
                }
            }
        }
        return succeeded;
    }

    private boolean assemble(String documentId) {
        try {
            bounded.assemble(documentId);
            return true;
        } catch (ActivityFailure failure) {
            durable.recordAssemblyFailed(documentId, failureCode(failure));
            return false;
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
