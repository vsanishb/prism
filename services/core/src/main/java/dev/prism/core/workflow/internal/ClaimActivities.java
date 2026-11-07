package dev.prism.core.workflow.internal;

import io.temporal.activity.ActivityInterface;
import java.util.List;

/** Short database work. Every method is safe to repeat, as Temporal may run an activity again. */
@ActivityInterface
public interface ClaimActivities {

    void begin(String claimId);

    List<String> documentsToExtract(String claimId);

    /** Returns whether every required document is present. */
    boolean consolidate(String claimId);

    void awaitInformation(String claimId);

    void readyForReview(String claimId);

    void recordExtractionFailed(String claimId, String documentId, String code);

    void recordConsolidationFailed(String claimId, String code);

    void recordInformationWaitExpired(String claimId);
}
