package dev.prism.core.workflow.internal;

import dev.prism.core.platform.outbox.Outbox;
import java.util.UUID;

/** Delivers one outbox event to the workflow engine. Must be safe to repeat for the same event. */
interface WorkflowDispatcher {

    void dispatch(Outbox.Event event, UUID claimId);
}
