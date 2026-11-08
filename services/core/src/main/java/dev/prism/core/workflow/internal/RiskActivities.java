package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** Risk screening: rules here, anomaly detection and an optional summary in the AI service. */
@ActivityInterface
public interface RiskActivities {

    /** Screens the claim's latest facts. Safe to repeat. */
    void screenRisk(String claimId);
}
