package dev.prism.core.workflow.internal;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.workflow.internal.StageRecorder.Stage;
import dev.prism.core.workflow.internal.StageRecorder.Status;
import io.temporal.failure.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AnalysisActivitiesImpl implements AnalysisActivities {

    private final ClaimIntelligence intelligence;
    private final StageRecorder stages;
    private final Heartbeats heartbeats;

    AnalysisActivitiesImpl(ClaimIntelligence intelligence, StageRecorder stages, Heartbeats heartbeats) {
        this.intelligence = intelligence;
        this.stages = stages;
        this.heartbeats = heartbeats;
    }

    @Override
    public void analyzePolicy(String claimId) {
        heartbeats.during(() -> analyze(claimId));
    }

    /** Separate from the heartbeat, so that tests can run it without a Temporal activity around it. */
    void analyze(String claimId) {
        UUID id = UUID.fromString(claimId);
        stages.record(id, Stage.POLICY_ANALYSIS, null, Status.PROCESSING, null);
        try {
            ClaimIntelligence.Analysis analysis = intelligence.analyze(id);
            stages.record(
                    id, Stage.POLICY_ANALYSIS, null, analysis.needsAttention() ? Status.REQUIRES_REVIEW : Status.COMPLETED, null);
        } catch (Failure failure) {
            // Only the code leaves here: failure text must not enter workflow history.
            throw failure.retryable()
                    ? ApplicationFailure.newFailure(failure.code(), failure.code())
                    : ApplicationFailure.newNonRetryableFailure(failure.code(), failure.code());
        }
    }
}
