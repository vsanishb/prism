package dev.prism.core.aiclient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The AI service's extraction contract. Field names match its JSON. */
public final class DocumentExtraction {

    private DocumentExtraction() {}

    public record Request(UUID documentId, String declaredType, String mediaType, byte[] content) {}

    public record Result(
            String pipelineVersion,
            int pageCount,
            Classification classification,
            List<Field> fields,
            List<LineItem> lineItems,
            List<ModelRun> runs) {}

    public record Classification(String documentType, String confidence, String declaredType) {}

    public record Field(
            String name,
            String kind,
            boolean critical,
            String state,
            String confidence,
            String rawValue,
            String value,
            Integer page,
            String quote,
            List<BigDecimal> region,
            String grounding,
            String reason) {}

    public record LineItem(
            String description,
            String quantity,
            String unitPrice,
            BigDecimal amount,
            String rawAmount,
            String state,
            int page,
            String quote,
            List<BigDecimal> region,
            String grounding) {}

    public record ModelRun(
            String task,
            String model,
            String provider,
            String promptVersion,
            int attempt,
            String outcome,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal costUsd,
            int latencyMs) {}

    /** Holds a stable code rather than detail, as this reaches workflow history and stage records. */
    public static final class Failure extends RuntimeException {

        private final String code;
        private final boolean retryable;
        private final transient List<ModelRun> runs;

        public Failure(String code, boolean retryable, List<ModelRun> runs) {
            super(code);
            this.code = code;
            this.retryable = retryable;
            this.runs = List.copyOf(runs);
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public List<ModelRun> runs() {
            return runs;
        }
    }
}
