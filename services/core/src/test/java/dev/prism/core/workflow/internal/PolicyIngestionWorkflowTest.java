package dev.prism.core.workflow.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyIngestionWorkflowTest {

    private static final String DOCUMENT = "018f0000-0000-7000-8000-0000000000d1";
    private static final long ASYNC_MILLIS = 10_000;

    private TestWorkflowEnvironment environment;
    private PolicyActivities database;
    private PolicyExtractionActivities model;
    private PolicyIngestionWorkflow workflow;

    @BeforeEach
    void start() {
        environment = TestWorkflowEnvironment.newInstance();
        database = mock(PolicyActivities.class, withSettings().withoutAnnotations());
        model = mock(PolicyExtractionActivities.class, withSettings().withoutAnnotations());
        environment.newWorker(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                .registerWorkflowImplementationTypes(PolicyIngestionWorkflowImpl.class);
        environment.newWorker(ClaimProcessingWorkflowImpl.DATABASE_QUEUE).registerActivitiesImplementations(database);
        environment.newWorker(ClaimProcessingWorkflowImpl.AI_QUEUE).registerActivitiesImplementations(model);
        environment.start();
        workflow = environment.getWorkflowClient().newWorkflowStub(
                PolicyIngestionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("policy-ingestion-" + DOCUMENT)
                        .setTaskQueue(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                        .build());
    }

    @AfterEach
    void stop() {
        environment.close();
    }

    private void finishes() throws Exception {
        WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, Void.class);
    }

    @Test
    void everyBatchIsReadThenTheWordingIsAssembled() throws Exception {
        when(database.beginIngestion(DOCUMENT)).thenReturn(true);
        when(database.pendingBatches(DOCUMENT)).thenReturn(List.of("1-6", "6-11", "11-14", "14-14"));

        WorkflowClient.start(workflow::ingest, DOCUMENT);
        finishes();

        verify(model, times(4)).extractPolicyBatch(any(), any());
        verify(database).assemble(DOCUMENT);
    }

    @Test
    void aReplacedWordingEndsAtOnce() throws Exception {
        when(database.beginIngestion(DOCUMENT)).thenReturn(false);

        WorkflowClient.start(workflow::ingest, DOCUMENT);
        finishes();

        verify(model, never()).extractPolicyBatch(any(), any());
        verify(database, never()).assemble(any());
    }

    @Test
    void aFailedBatchWaitsForARetryAndThenFinishes() throws Exception {
        when(database.beginIngestion(DOCUMENT)).thenReturn(true);
        when(database.pendingBatches(DOCUMENT)).thenReturn(List.of("1-2")).thenReturn(List.of("1-2"));
        doThrow(ApplicationFailure.newNonRetryableFailure("bad", "model_output_invalid"))
                .doNothing()
                .when(model).extractPolicyBatch(DOCUMENT, "1-2");

        WorkflowClient.start(workflow::ingest, DOCUMENT);
        verify(database, timeout(ASYNC_MILLIS)).recordBatchFailed(DOCUMENT, "1-2", "model_output_invalid");
        verify(database, never()).assemble(any());

        workflow.retryRequested();
        finishes();
        verify(database).assemble(DOCUMENT);
    }

    @Test
    void transientFailuresAreRetriedQuietly() throws Exception {
        when(database.beginIngestion(DOCUMENT)).thenReturn(true);
        when(database.pendingBatches(DOCUMENT)).thenReturn(List.of("1-2"));
        doThrow(ApplicationFailure.newFailure("busy", "model_unavailable"))
                .doNothing()
                .when(model).extractPolicyBatch(DOCUMENT, "1-2");

        WorkflowClient.start(workflow::ingest, DOCUMENT);
        environment.sleep(Duration.ofMinutes(1));
        finishes();

        verify(database, never()).recordBatchFailed(any(), any(), any());
    }

    @Test
    void aFailedAssemblyIsRecordedAndRetried() throws Exception {
        when(database.beginIngestion(DOCUMENT)).thenReturn(true);
        when(database.pendingBatches(DOCUMENT)).thenReturn(List.of());
        doThrow(ApplicationFailure.newNonRetryableFailure("defect", "assembly_failed"))
                .doNothing()
                .when(database).assemble(DOCUMENT);

        WorkflowClient.start(workflow::ingest, DOCUMENT);
        verify(database, timeout(ASYNC_MILLIS)).recordAssemblyFailed(DOCUMENT, "assembly_failed");
        workflow.retryRequested();
        finishes();

        verify(database, times(2)).assemble(DOCUMENT);
    }
}
