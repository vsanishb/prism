package dev.prism.core.workflow.internal;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Sends heartbeats during the model call, which is one long blocking request. Without them, Temporal
 * notices a crashed worker only when the five-minute timeout ends, and the claim appears to hang.
 */
@Component
class ExtractionActivitiesImpl implements ExtractionActivities, DisposableBean {

    static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_INTERVAL = HEARTBEAT_TIMEOUT.dividedBy(3);

    private final DocumentExtractionStep step;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "extraction-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    ExtractionActivitiesImpl(DocumentExtractionStep step) {
        this.step = step;
    }

    @Override
    public void extract(String claimId, String documentId) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        ScheduledFuture<?> beating = heartbeats.scheduleAtFixedRate(
                () -> beat(context), 0, HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        try {
            step.extract(claimId, documentId);
        } finally {
            beating.cancel(false);
        }
    }

    /** A failed heartbeat must not cancel the schedule: Temporal may just be briefly unreachable. */
    private static void beat(ActivityExecutionContext context) {
        try {
            context.heartbeat(null);
        } catch (RuntimeException ignored) {
            // The server's own timeout decides what happens if heartbeats stop arriving.
        }
    }

    @Override
    public void destroy() {
        heartbeats.shutdownNow();
    }
}
