package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;

/** The Policy Agent's model calls, on the AI queue with its concurrency limit. */
@ActivityInterface
public interface AnalysisActivities {

    /** Analyzes the claim's latest facts against its policy. Safe to repeat. */
    void analyzePolicy(String claimId);
}
