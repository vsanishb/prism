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
 * Orchestration only: every effect is in an activity, and this class must stay deterministic. Change
 * its flow only with {@code Workflow.getVersion}, as running claims replay this code.
 *
 * <p>A claim is never lost to a failure. A failed stage is recorded, and the workflow then waits for
 * a person to retry or replace a document, instead of ending.
 */
public class ClaimProcessingWorkflowImpl implements ClaimProcessingWorkflow {

    static final String WORKFLOW_QUEUE = "prism-workflows";
    static final String DATABASE_QUEUE = "prism-activities-db";
    static final String AI_QUEUE = "prism-activities-ai";

    /** Protects model rate limits. Worker concurrency is the outer bound, across all claims. */
    private static final int MAX_PARALLEL_DOCUMENTS = 3;

    static final String POLICY_ANALYSIS_CHANGE = "policy-analysis";
    static final String RISK_SCREENING_CHANGE = "risk-screening";

    /** State changes must eventually land, however long a database outage lasts. */
    private final ClaimActivities durable = Workflow.newActivityStub(
            ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(DATABASE_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .build())
                    .build());

    /** Bounded, so that a defect in consolidation surfaces as a failed stage and not a silent loop. */
    private final ClaimActivities bounded = Workflow.newActivityStub(
            ClaimActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(DATABASE_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final ExtractionActivities extraction = Workflow.newActivityStub(
            ExtractionActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(AI_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Heartbeats.TIMEOUT)
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(3)
                            .setMaximumAttempts(4)
                            .build())
                    .build());

    /** Many model calls in sequence: longer than one document, and it heartbeats throughout. */
    private final AnalysisActivities analysis = Workflow.newActivityStub(
            AnalysisActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(AI_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Heartbeats.TIMEOUT)
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(3)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    /** Model calls are few and short, but it heartbeats like analysis. */
    private final RiskActivities risk = Workflow.newActivityStub(
            RiskActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(AI_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Heartbeats.TIMEOUT)
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(3)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private boolean analysisRetryable;
    private boolean riskRetryable;
    private boolean documentsChanged;
    private boolean retryRequested;
    private boolean withdrawn;
    private boolean complete;

    @Override
    public void process(String claimId, long informationWaitSeconds) {
        durable.begin(claimId);
        boolean waitExpired = false;
        while (!withdrawn) {
            documentsChanged = false;
            retryRequested = false;

            if (!extractDocuments(claimId) || !consolidate(claimId)) {
                Workflow.await(() -> retryRequested || documentsChanged || withdrawn);
                continue;
            }
            // Anything added while this cycle ran is not in its result, so run another.
            if (documentsChanged) {
                continue;
            }
            if (!complete && !waitExpired) {
                durable.awaitInformation(claimId);
                boolean answered = Workflow.await(
                        Duration.ofSeconds(informationWaitSeconds), () -> documentsChanged || withdrawn);
                if (answered) {
                    if (!withdrawn) {
                        durable.begin(claimId);
                    }
                    continue;
                }
                waitExpired = true;
                durable.recordInformationWaitExpired(claimId);
            }
            // Claims started before M6 replay without this stage. Analysis assists review and never
            // holds it: a failed analysis is shown, and a retry runs the cycle again (D-080).
            if (Workflow.getVersion(POLICY_ANALYSIS_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1) {
                retryRequested = false;
                analysisRetryable = !analyzePolicy(claimId);
            }
            // Claims started before M7 replay without this stage. Like analysis, screening assists
            // review and never holds it: a failure is shown, and a retry runs the cycle again.
            if (Workflow.getVersion(RISK_SCREENING_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1) {
                retryRequested = false;
                riskRetryable = !screenRisk(claimId);
            }
            durable.readyForReview(claimId);
            // Review arrives in a later milestone. Until then, new documents reopen processing.
            Workflow.await(() -> documentsChanged || withdrawn || ((analysisRetryable || riskRetryable) && retryRequested));
            if (!withdrawn) {
                waitExpired = false;
                durable.begin(claimId);
            }
        }
    }

    private boolean extractDocuments(String claimId) {
        List<String> pending = durable.documentsToExtract(claimId);
        boolean succeeded = true;
        for (int start = 0; start < pending.size(); start += MAX_PARALLEL_DOCUMENTS) {
            List<String> batch = pending.subList(start, Math.min(start + MAX_PARALLEL_DOCUMENTS, pending.size()));
            List<Promise<Void>> running = new ArrayList<>();
            for (String documentId : batch) {
                running.add(Async.procedure(extraction::extract, claimId, documentId));
            }
            for (int index = 0; index < batch.size(); index++) {
                try {
                    running.get(index).get();
                } catch (ActivityFailure failure) {
                    succeeded = false;
                    durable.recordExtractionFailed(claimId, batch.get(index), failureCode(failure));
                }
            }
        }
        return succeeded;
    }

    /** Records a failure, such as a policy not yet published, instead of waiting on it. */
    private boolean analyzePolicy(String claimId) {
        try {
            analysis.analyzePolicy(claimId);
            return true;
        } catch (ActivityFailure failure) {
            durable.recordAnalysisFailed(claimId, failureCode(failure));
            return false;
        }
    }

    private boolean screenRisk(String claimId) {
        try {
            risk.screenRisk(claimId);
            return true;
        } catch (ActivityFailure failure) {
            durable.recordRiskScreeningFailed(claimId, failureCode(failure));
            return false;
        }
    }

    private boolean consolidate(String claimId) {
        try {
            complete = bounded.consolidate(claimId);
            return true;
        } catch (ActivityFailure failure) {
            durable.recordConsolidationFailed(claimId, failureCode(failure));
            return false;
        }
    }

    private static String failureCode(ActivityFailure failure) {
        return failure.getCause() instanceof ApplicationFailure application ? application.getType() : "timed_out";
    }

    @Override
    public void documentsChanged() {
        documentsChanged = true;
    }

    @Override
    public void retryRequested() {
        retryRequested = true;
    }

    @Override
    public void claimWithdrawn() {
        withdrawn = true;
    }
}
