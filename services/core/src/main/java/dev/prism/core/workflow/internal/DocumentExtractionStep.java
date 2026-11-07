package dev.prism.core.workflow.internal;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.workflow.internal.StageRecorder.Stage;
import dev.prism.core.workflow.internal.StageRecorder.Status;
import io.temporal.failure.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** What an extraction activity does, apart from reporting that it is alive. */
@Component
class DocumentExtractionStep {

    private final ClaimIntelligence intelligence;
    private final StageRecorder stages;

    DocumentExtractionStep(ClaimIntelligence intelligence, StageRecorder stages) {
        this.intelligence = intelligence;
        this.stages = stages;
    }

    void extract(String claimId, String documentId) {
        UUID claim = UUID.fromString(claimId);
        UUID document = UUID.fromString(documentId);
        stages.record(claim, Stage.DOCUMENT_EXTRACTION, document, Status.PROCESSING, null);
        try {
            intelligence.extract(claim, document);
        } catch (Failure failure) {
            // The code is the only detail that leaves here: failure text must not enter workflow history.
            throw failure.retryable()
                    ? ApplicationFailure.newFailure(failure.code(), failure.code())
                    : ApplicationFailure.newNonRetryableFailure(failure.code(), failure.code());
        }
        stages.record(claim, Stage.DOCUMENT_EXTRACTION, document, Status.COMPLETED, null);
    }
}
