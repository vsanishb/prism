package dev.prism.core.workflow.internal;

import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.intelligence.ClaimIntelligence;
import dev.prism.core.workflow.internal.StageRecorder.Stage;
import dev.prism.core.workflow.internal.StageRecorder.Status;
import io.temporal.failure.ApplicationFailure;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class RiskActivitiesImpl implements RiskActivities {

    private final ClaimIntelligence intelligence;
    private final StageRecorder stages;
    private final Heartbeats heartbeats;

    RiskActivitiesImpl(ClaimIntelligence intelligence, StageRecorder stages, Heartbeats heartbeats) {
        this.intelligence = intelligence;
        this.stages = stages;
        this.heartbeats = heartbeats;
    }

    @Override
    public void screenRisk(String claimId) {
        heartbeats.during(() -> screen(claimId));
    }

    /**
     * Separate from the heartbeat, so that tests can run it without a Temporal activity around it. The
     * band is the stage's detail, so the processing screen can show it. A medium or high band asks for
     * a closer look, and changes nothing else about the claim.
     */
    void screen(String claimId) {
        UUID id = UUID.fromString(claimId);
        stages.record(id, Stage.RISK_SCREENING, null, Status.PROCESSING, null);
        try {
            ClaimIntelligence.Screening screening = intelligence.screenRisk(id);
            if (screening.band() == null) {
                return;
            }
            stages.record(id, Stage.RISK_SCREENING, null,
                    "LOW".equals(screening.band()) ? Status.COMPLETED : Status.REQUIRES_REVIEW, screening.band());
        } catch (Failure failure) {
            // Only the code leaves here: failure text must not enter workflow history.
            throw failure.retryable()
                    ? ApplicationFailure.newFailure(failure.code(), failure.code())
                    : ApplicationFailure.newNonRetryableFailure(failure.code(), failure.code());
        }
    }
}
