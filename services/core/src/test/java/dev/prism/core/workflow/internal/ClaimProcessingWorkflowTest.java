package dev.prism.core.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Runs the real workflow on Temporal's in-memory server, with time skipping and mocked activities. */
class ClaimProcessingWorkflowTest {

    private static final String CLAIM = "018f0000-0000-7000-8000-000000000001";
    private static final String DOCUMENT_A = "018f0000-0000-7000-8000-00000000000a";
    private static final String DOCUMENT_B = "018f0000-0000-7000-8000-00000000000b";
    private static final long WAIT_SECONDS = Duration.ofDays(14).toSeconds();
    private static final long ASYNC_MILLIS = 10_000;
    private static final Path SAVED_HISTORY = Path.of("src/test/resources/histories/claim-processing-v1.json");

    private TestWorkflowEnvironment environment;
    private ClaimActivities claims;
    private ExtractionActivities extraction;
    private ClaimProcessingWorkflow workflow;
    private WorkflowExecution execution;

    @BeforeEach
    void start() {
        environment = TestWorkflowEnvironment.newInstance();
        claims = mock(ClaimActivities.class, withSettings().withoutAnnotations());
        extraction = mock(ExtractionActivities.class, withSettings().withoutAnnotations());
        environment.newWorker(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                .registerWorkflowImplementationTypes(ClaimProcessingWorkflowImpl.class);
        environment.newWorker(ClaimProcessingWorkflowImpl.DATABASE_QUEUE).registerActivitiesImplementations(claims);
        environment.newWorker(ClaimProcessingWorkflowImpl.AI_QUEUE).registerActivitiesImplementations(extraction);
        environment.start();
        workflow = environment.getWorkflowClient().newWorkflowStub(
                ClaimProcessingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("claim-" + CLAIM)
                        .setTaskQueue(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE)
                        .build());
    }

    @AfterEach
    void stop() {
        environment.close();
    }

    private void run() {
        execution = WorkflowClient.start(workflow::process, CLAIM, WAIT_SECONDS);
    }

    private void finish() throws TimeoutException {
        workflow.claimWithdrawn();
        WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, Void.class);
    }

    private void pending(String... documents) {
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(documents)).thenReturn(List.of());
    }

    @Test
    void aCompleteClaimReachesReviewInOrder() throws Exception {
        pending(DOCUMENT_A, DOCUMENT_B);
        when(claims.consolidate(CLAIM)).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        InOrder order = inOrder(claims, extraction);
        order.verify(claims).begin(CLAIM);
        order.verify(claims).documentsToExtract(CLAIM);
        order.verify(claims).consolidate(CLAIM);
        order.verify(claims).readyForReview(CLAIM);
        verify(extraction).extract(CLAIM, DOCUMENT_A);
        verify(extraction).extract(CLAIM, DOCUMENT_B);
        verify(claims, never()).awaitInformation(any());
    }

    @Test
    void transientExtractionFailuresAreRetriedWithoutAnyoneNoticing() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(true);
        doThrow(ApplicationFailure.newFailure("rate limited", "model_unavailable"))
                .doNothing()
                .when(extraction).extract(CLAIM, DOCUMENT_A);

        run();
        environment.sleep(Duration.ofMinutes(1));
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        verify(extraction, times(2)).extract(CLAIM, DOCUMENT_A);
        verify(claims, never()).recordExtractionFailed(any(), any(), any());
    }

    @Test
    void exhaustedRetriesRecordAFailureAndWaitForARetryInsteadOfEnding() throws Exception {
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_A));
        when(claims.consolidate(CLAIM)).thenReturn(true);
        doThrow(ApplicationFailure.newFailure("outage", "model_unavailable")).when(extraction).extract(CLAIM, DOCUMENT_A);

        run();
        environment.sleep(Duration.ofMinutes(5));
        verify(claims, timeout(ASYNC_MILLIS)).recordExtractionFailed(CLAIM, DOCUMENT_A, "model_unavailable");
        verify(extraction, times(4)).extract(CLAIM, DOCUMENT_A);
        verify(claims, never()).consolidate(any());

        doNothing().when(extraction).extract(CLAIM, DOCUMENT_A);
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_A)).thenReturn(List.of());
        workflow.retryRequested();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();
    }

    @Test
    void aNonRetryableFailureIsNotRepeated() throws Exception {
        pending(DOCUMENT_A);
        doThrow(ApplicationFailure.newNonRetryableFailure("bad file", "document_unreadable"))
                .when(extraction).extract(CLAIM, DOCUMENT_A);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).recordExtractionFailed(CLAIM, DOCUMENT_A, "document_unreadable");
        finish();

        verify(extraction, times(1)).extract(CLAIM, DOCUMENT_A);
    }

    @Test
    void oneFailedDocumentDoesNotStopTheOthers() throws Exception {
        pending(DOCUMENT_A, DOCUMENT_B);
        doThrow(ApplicationFailure.newNonRetryableFailure("bad file", "document_unreadable"))
                .when(extraction).extract(CLAIM, DOCUMENT_A);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).recordExtractionFailed(CLAIM, DOCUMENT_A, "document_unreadable");
        finish();

        verify(extraction).extract(CLAIM, DOCUMENT_B);
    }

    @Test
    void replacingAFailedDocumentAlsoResumes() throws Exception {
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_A));
        when(claims.consolidate(CLAIM)).thenReturn(true);
        doThrow(ApplicationFailure.newNonRetryableFailure("bad file", "document_unreadable"))
                .when(extraction).extract(CLAIM, DOCUMENT_A);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).recordExtractionFailed(CLAIM, DOCUMENT_A, "document_unreadable");

        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_B)).thenReturn(List.of());
        workflow.documentsChanged();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();
    }

    @Test
    void aFailedConsolidationWaitsForARetry() throws Exception {
        pending();
        when(claims.consolidate(CLAIM))
                .thenThrow(ApplicationFailure.newNonRetryableFailure("defect", "consolidation_failed"))
                .thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).recordConsolidationFailed(CLAIM, "consolidation_failed");

        workflow.retryRequested();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();
    }

    @Test
    void missingDocumentsWaitAndNewDocumentsResumeProcessing() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        verify(claims, never()).readyForReview(any());

        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_B)).thenReturn(List.of());
        workflow.documentsChanged();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        verify(claims, times(2)).begin(CLAIM);
        verify(extraction).extract(CLAIM, DOCUMENT_B);
    }

    @Test
    void anExpiredWaitGoesToReviewWithoutTheDocuments() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        environment.sleep(Duration.ofDays(15));
        verify(claims, timeout(ASYNC_MILLIS)).recordInformationWaitExpired(CLAIM);
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();
    }

    @Test
    void theWaitDoesNotExpireEarly() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        environment.sleep(Duration.ofDays(13));
        verify(claims, never()).recordInformationWaitExpired(any());
        finish();
    }

    @Test
    void documentsAddedDuringReviewReopenProcessing() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);

        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_B)).thenReturn(List.of());
        workflow.documentsChanged();
        verify(claims, timeout(ASYNC_MILLIS).times(2)).readyForReview(CLAIM);
        finish();

        verify(extraction).extract(CLAIM, DOCUMENT_B);
    }

    @Test
    void duplicateSignalsCauseOnlyOneMoreCycle() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of());
        workflow.documentsChanged();
        workflow.documentsChanged();
        workflow.documentsChanged();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        verify(claims, times(2)).consolidate(CLAIM);
    }

    @Test
    void withdrawalEndsTheWorkflowDuringAWait() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        finish();

        verify(claims, times(1)).begin(CLAIM);
        verify(claims, never()).readyForReview(any());
    }

    @Test
    void withdrawalEndsTheWorkflowAfterAFailure() throws Exception {
        pending(DOCUMENT_A);
        doThrow(ApplicationFailure.newNonRetryableFailure("bad file", "document_unreadable"))
                .when(extraction).extract(any(), eq(DOCUMENT_A));

        run();
        verify(claims, timeout(ASYNC_MILLIS)).recordExtractionFailed(CLAIM, DOCUMENT_A, "document_unreadable");
        finish();
    }

    @Test
    void fanOutIsBoundedAcrossBatches() throws Exception {
        pending("d1", "d2", "d3", "d4", "d5", "d6", "d7");
        when(claims.consolidate(CLAIM)).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        verify(extraction, times(7)).extract(eq(CLAIM), any());
    }

    /**
     * A saved history replayed against today's code. If this fails, the change would break claims
     * already running: guard it with Workflow.getVersion, and do not simply regenerate the file.
     */
    @Test
    void savedHistoryStillReplays() throws Exception {
        WorkflowReplayer.replayWorkflowExecution(Files.readString(SAVED_HISTORY), ClaimProcessingWorkflowImpl.class);
    }

    @Test
    void aFreshHistoryReplaysAndCarriesNoClaimContent() throws Exception {
        pending(DOCUMENT_A);
        when(claims.consolidate(CLAIM)).thenReturn(false).thenReturn(true);

        run();
        verify(claims, timeout(ASYNC_MILLIS)).awaitInformation(CLAIM);
        when(claims.documentsToExtract(CLAIM)).thenReturn(List.of(DOCUMENT_B)).thenReturn(List.of());
        workflow.documentsChanged();
        verify(claims, timeout(ASYNC_MILLIS)).readyForReview(CLAIM);
        finish();

        WorkflowExecutionHistory history = environment.getWorkflowClient().fetchHistory(execution.getWorkflowId());
        WorkflowReplayer.replayWorkflowExecution(history, ClaimProcessingWorkflowImpl.class);
        if (System.getenv("PRISM_WRITE_WORKFLOW_HISTORY") != null) {
            Files.createDirectories(SAVED_HISTORY.getParent());
            Files.writeString(SAVED_HISTORY, history.toJson(true));
        }
        assertThat(history.toJson(false)).doesNotContain("Patient", "patient", "diagnosis", "amount");
        reset(claims);
    }
}
