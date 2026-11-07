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
 * Keeps a long activity visibly alive during one blocking model call (D-063). Without this, Temporal
 * notices a crashed worker only when the five-minute timeout ends.
 */
@Component
class Heartbeats implements DisposableBean {

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INTERVAL = TIMEOUT.dividedBy(3);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "activity-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** Runs the work inside the current activity, sending heartbeats until it ends. */
    void during(Runnable work) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        ScheduledFuture<?> beating =
                scheduler.scheduleAtFixedRate(() -> beat(context), 0, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        try {
            work.run();
        } finally {
            beating.cancel(false);
        }
    }

    /** A failed heartbeat must not stop the schedule: Temporal may just be briefly unreachable. */
    private static void beat(ActivityExecutionContext context) {
        try {
            context.heartbeat(null);
        } catch (RuntimeException ignored) {
            // The server's own timeout decides what happens if heartbeats stop arriving.
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
