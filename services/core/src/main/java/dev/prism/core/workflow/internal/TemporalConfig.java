package dev.prism.core.workflow.internal;

import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.workflow.WorkflowHealth;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** The worker and relay. Off in tests and the seeder, which have no workflow engine to reach. */
@Configuration
@ConditionalOnProperty(name = "prism.temporal.enabled", havingValue = "true")
class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(PrismProperties properties) {
        // Lazy, so that the core service starts and serves users while Temporal is down.
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.temporal().target())
                .build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, PrismProperties properties) {
        return WorkflowClient.newInstance(
                stubs, WorkflowClientOptions.newBuilder().setNamespace(properties.temporal().namespace()).build());
    }

    @Bean
    WorkerFactory workerFactory(
            WorkflowClient client,
            ClaimActivities claimActivities,
            ExtractionActivities extractionActivities,
            PolicyActivitiesImpl policyActivities,
            PrismProperties properties) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker workflows = factory.newWorker(ClaimProcessingWorkflowImpl.WORKFLOW_QUEUE);
        workflows.registerWorkflowImplementationTypes(ClaimProcessingWorkflowImpl.class, PolicyIngestionWorkflowImpl.class);

        Worker database = factory.newWorker(ClaimProcessingWorkflowImpl.DATABASE_QUEUE);
        database.registerActivitiesImplementations(claimActivities);
        // Registered under its database interface only. Its model calls run on the AI queue below.
        database.registerActivitiesImplementations(new PolicyDatabaseActivities(policyActivities));

        Worker ai = factory.newWorker(
                ClaimProcessingWorkflowImpl.AI_QUEUE,
                WorkerOptions.newBuilder()
                        .setMaxConcurrentActivityExecutionSize(properties.temporal().maxConcurrentExtractions())
                        .build());
        ai.registerActivitiesImplementations(extractionActivities, new PolicyModelActivities(policyActivities));
        return factory;
    }

    @Bean
    TemporalWorkerLifecycle temporalWorkerLifecycle(WorkerFactory factory) {
        return new TemporalWorkerLifecycle(factory);
    }

    @Bean
    WorkflowDispatcher workflowDispatcher(WorkflowClient client, PrismProperties properties) {
        return new TemporalWorkflowDispatcher(client, properties.temporal().informationWait());
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, WorkflowDispatcher dispatcher) {
        return new OutboxRelay(jdbc, dispatcher);
    }

    @Bean
    OutboxRelaySchedule outboxRelaySchedule(OutboxRelay relay) {
        return new OutboxRelaySchedule(relay);
    }

    @Bean
    WorkflowHealth workflowHealth(WorkflowServiceStubs stubs) {
        return new TemporalWorkflowHealth(stubs);
    }

    /** Exposes only the database methods, so that each queue serves exactly its own activities. */
    record PolicyDatabaseActivities(PolicyActivitiesImpl delegate) implements PolicyActivities {
        @Override
        public boolean beginIngestion(String documentId) {
            return delegate.beginIngestion(documentId);
        }

        @Override
        public java.util.List<String> pendingBatches(String documentId) {
            return delegate.pendingBatches(documentId);
        }

        @Override
        public void recordBatchFailed(String documentId, String range, String code) {
            delegate.recordBatchFailed(documentId, range, code);
        }

        @Override
        public void assemble(String documentId) {
            delegate.assemble(documentId);
        }

        @Override
        public void recordAssemblyFailed(String documentId, String code) {
            delegate.recordAssemblyFailed(documentId, code);
        }
    }

    record PolicyModelActivities(PolicyActivitiesImpl delegate) implements PolicyExtractionActivities {
        @Override
        public void extractPolicyBatch(String documentId, String range) {
            delegate.extractPolicyBatch(documentId, range);
        }
    }

    static class OutboxRelaySchedule {

        private final OutboxRelay relay;

        OutboxRelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${prism.temporal.relay-interval}")
        void publish() {
            relay.publishPending();
        }
    }
}
