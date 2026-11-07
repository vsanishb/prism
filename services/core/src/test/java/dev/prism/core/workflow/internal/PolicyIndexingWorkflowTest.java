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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyIndexingWorkflowTest {

    private static final String VERSION = "018f0000-0000-7000-8000-0000000000e1";
    private static final long ASYNC_MILLIS = 10_000;

    private TestWorkflowEnvironment environment;
    private IndexingActivities database;
    private IndexBuildActivities model;
    private PolicyIndexingWorkflow workflow;

    @BeforeEach
    void start() {
        environment = TestWorkflowEnvironment.newInstance();
        database = mock(IndexingActivities.class, withSettings().withoutAnnotations());
        model = mock(IndexBuildActivities.class, withSettings().withoutAnnotations());
        environment.newWorker(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                .registerWorkflowImplementationTypes(PolicyIndexingWorkflowImpl.class);
        environment.newWorker(ClaimProcessingWorkflowImpl.DATABASE_QUEUE).registerActivitiesImplementations(database);
        environment.newWorker(ClaimProcessingWorkflowImpl.AI_QUEUE).registerActivitiesImplementations(model);
        environment.start();
        workflow = environment.getWorkflowClient().newWorkflowStub(
                PolicyIndexingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(TemporalWorkflowDispatcher.indexingId(java.util.UUID.fromString(VERSION)))
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
    void aPublishedVersionIsIndexed() throws Exception {
        when(database.beginIndexing(VERSION)).thenReturn(true);

        WorkflowClient.start(workflow::index, VERSION);
        finishes();

        verify(model).buildIndex(VERSION);
        verify(database, never()).recordIndexingFailed(any(), any());
    }

    @Test
    void aVersionThatIsNotPublishedEndsAtOnce() throws Exception {
        when(database.beginIndexing(VERSION)).thenReturn(false);

        WorkflowClient.start(workflow::index, VERSION);
        finishes();

        verify(model, never()).buildIndex(any());
    }

    @Test
    void transientFailuresAreRetriedQuietly() throws Exception {
        when(database.beginIndexing(VERSION)).thenReturn(true);
        doThrow(ApplicationFailure.newFailure("busy", "model_unavailable")).doNothing().when(model).buildIndex(VERSION);

        WorkflowClient.start(workflow::index, VERSION);
        environment.sleep(Duration.ofMinutes(1));
        finishes();

        verify(database, never()).recordIndexingFailed(any(), any());
    }

    @Test
    void aFailureIsRecordedAndWaitsForARetry() throws Exception {
        when(database.beginIndexing(VERSION)).thenReturn(true);
        doThrow(ApplicationFailure.newNonRetryableFailure("refused", "model_rejected"))
                .doNothing()
                .when(model).buildIndex(VERSION);

        WorkflowClient.start(workflow::index, VERSION);
        verify(database, timeout(ASYNC_MILLIS)).recordIndexingFailed(VERSION, "model_rejected");

        workflow.retryRequested();
        finishes();
        verify(model, times(2)).buildIndex(VERSION);
        verify(database, times(2)).beginIndexing(VERSION);
    }
}
