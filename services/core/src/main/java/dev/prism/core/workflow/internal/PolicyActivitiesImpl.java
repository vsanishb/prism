package dev.prism.core.workflow.internal;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.policyknowledge.PolicyIngestion;
import dev.prism.core.policyknowledge.PolicyIngestion.PageRange;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PolicyActivitiesImpl implements PolicyActivities, PolicyExtractionActivities {

    private final PolicyIngestion ingestion;
    private final Heartbeats heartbeats;

    PolicyActivitiesImpl(PolicyIngestion ingestion, Heartbeats heartbeats) {
        this.ingestion = ingestion;
        this.heartbeats = heartbeats;
    }

    @Override
    public boolean beginIngestion(String documentId) {
        return ingestion.begin(UUID.fromString(documentId));
    }

    @Override
    public List<String> pendingBatches(String documentId) {
        return ingestion.pendingBatches(UUID.fromString(documentId)).stream().map(PageRange::toString).toList();
    }

    @Override
    public void recordBatchFailed(String documentId, String range, String code) {
        ingestion.recordBatchFailed(UUID.fromString(documentId), PageRange.parse(range), code);
    }

    @Override
    public void assemble(String documentId) {
        ingestion.assemble(UUID.fromString(documentId));
    }

    @Override
    public void recordAssemblyFailed(String documentId, String code) {
        ingestion.recordAssemblyFailed(UUID.fromString(documentId), code);
    }

    @Override
    public void extractPolicyBatch(String documentId, String range) {
        heartbeats.during(() -> extractBatch(documentId, range));
    }

    /** Separate from the heartbeat, so that tests can run it without a Temporal activity around it. */
    void extractBatch(String documentId, String range) {
        try {
            ingestion.extractBatch(UUID.fromString(documentId), PageRange.parse(range));
        } catch (Failure failure) {
            // Only the code leaves here: failure text must not enter workflow history.
            throw failure.retryable()
                    ? ApplicationFailure.newFailure(failure.code(), failure.code())
                    : ApplicationFailure.newNonRetryableFailure(failure.code(), failure.code());
        }
    }
}
