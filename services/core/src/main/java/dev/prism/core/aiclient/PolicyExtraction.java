package dev.prism.core.aiclient;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The AI service's policy page contract. Field names match its JSON. */
public final class PolicyExtraction {

    private PolicyExtraction() {}

    public record Request(UUID documentId, String mediaType, int firstPage, int lastPage, byte[] content) {}

    public record Result(
            String pipelineVersion, int pageCount, int firstPage, int lastPage, List<Provision> provisions, List<ModelRun> runs) {}

    public record Provision(
            String key,
            String type,
            String title,
            String text,
            int page,
            String quote,
            List<BigDecimal> region,
            String grounding,
            boolean textVerified,
            String parentKey,
            List<String> exceptions,
            List<Reference> references,
            List<Value> values,
            boolean continuesOnNextPage,
            boolean continuedFromPreviousPage) {}

    public record Reference(String targetKey, String relation, boolean statedInText) {}

    public record Value(
            String name,
            String kind,
            String rawValue,
            String value,
            String unit,
            String qualifier,
            String state,
            Integer page,
            String quote,
            List<BigDecimal> region,
            String grounding,
            String problem) {}
}
