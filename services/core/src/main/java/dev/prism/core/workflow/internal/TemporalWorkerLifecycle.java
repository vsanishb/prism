package dev.prism.core.workflow.internal;

import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the worker in the background and keeps trying. The factory refuses to start while Temporal
 * is unreachable, and that must not stop the core service: screens read Postgres, and the outbox
 * holds events until the worker connects.
 */
class TemporalWorkerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerLifecycle.class);
    private static final long RETRY_DELAY_MILLIS = 5_000;
    private static final long SHUTDOWN_SECONDS = 20;

    private final WorkerFactory factory;
    private volatile boolean running;
    private Thread starter;

    TemporalWorkerLifecycle(WorkerFactory factory) {
        this.factory = factory;
    }

    @Override
    public void start() {
        running = true;
        starter = new Thread(this::startWhenReachable, "temporal-worker-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void startWhenReachable() {
        while (running) {
            try {
                factory.start();
                log.info("Temporal worker started");
                return;
            } catch (RuntimeException exception) {
                log.warn("Temporal is not reachable yet: {}", exception.getClass().getSimpleName());
            }
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        starter.interrupt();
        if (factory.isStarted()) {
            factory.shutdown();
            factory.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
