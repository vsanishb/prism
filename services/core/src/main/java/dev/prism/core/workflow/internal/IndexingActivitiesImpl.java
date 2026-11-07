package dev.prism.core.workflow.internal;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.retrieval.PolicyIndexing;
import io.temporal.failure.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class IndexingActivitiesImpl implements IndexingActivities, IndexBuildActivities {

    private final PolicyIndexing indexing;
    private final Heartbeats heartbeats;

    IndexingActivitiesImpl(PolicyIndexing indexing, Heartbeats heartbeats) {
        this.indexing = indexing;
        this.heartbeats = heartbeats;
    }

    @Override
    public boolean beginIndexing(String versionId) {
        return indexing.begin(UUID.fromString(versionId));
    }

    @Override
    public void recordIndexingFailed(String versionId, String code) {
        indexing.recordFailed(UUID.fromString(versionId), code);
    }

    @Override
    public void buildIndex(String versionId) {
        heartbeats.during(() -> build(versionId));
    }

    /** Separate from the heartbeat, so that tests can run it without a Temporal activity around it. */
    void build(String versionId) {
        try {
            indexing.build(UUID.fromString(versionId));
        } catch (Failure failure) {
            // Only the code leaves here: failure text must not enter workflow history.
            throw failure.retryable()
                    ? ApplicationFailure.newFailure(failure.code(), failure.code())
                    : ApplicationFailure.newNonRetryableFailure(failure.code(), failure.code());
        }
    }
}
