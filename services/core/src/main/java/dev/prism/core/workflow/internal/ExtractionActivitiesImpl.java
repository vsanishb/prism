package dev.prism.core.workflow.internal;

import org.springframework.stereotype.Component;

@Component
class ExtractionActivitiesImpl implements ExtractionActivities {

    private final DocumentExtractionStep step;
    private final Heartbeats heartbeats;

    ExtractionActivitiesImpl(DocumentExtractionStep step, Heartbeats heartbeats) {
        this.step = step;
        this.heartbeats = heartbeats;
    }

    @Override
    public void extract(String claimId, String documentId) {
        heartbeats.during(() -> step.extract(claimId, documentId));
    }
}
