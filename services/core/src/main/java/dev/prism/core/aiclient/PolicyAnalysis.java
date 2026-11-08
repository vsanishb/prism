package dev.prism.core.aiclient;

import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The AI service's Policy Agent contract. Field names match its JSON. */
public final class PolicyAnalysis {

    private PolicyAnalysis() {}

    /** De-identified: no names, hospitals, doctors, or policy numbers. */
    public record Request(
            UUID claimId,
            UUID versionId,
            String claimType,
            String currency,
            BigDecimal sumInsured,
            LocalDate coverStart,
            List<Fact> facts,
            List<LineItem> lineItems,
            List<PolicyValue> policyValues) {}

    public record Fact(UUID id, String name, String kind, String state, String value) {}

    public record LineItem(UUID id, String description, BigDecimal amount) {}

    public record PolicyValue(
            UUID id, UUID provisionId, String name, String kind, String value, String unit, String qualifier) {}

    public record Result(
            UUID claimId,
            UUID versionId,
            String pipelineVersion,
            String planPromptVersion,
            String analysisPromptVersion,
            List<Finding> findings,
            int degradedSearches,
            List<ModelRun> runs) {}

    public record Finding(
            int position,
            String kind,
            String question,
            String status,
            String proposedStatus,
            List<UUID> factsUsed,
            List<Citation> citations,
            String reasoning,
            List<String> unresolved,
            List<AppliedValue> appliedValues,
            List<String> problems,
            int rejectedCitations,
            int rounds,
            List<String> queries) {}

    public record Citation(UUID provisionId, String key, String title, String type, String quote) {}

    public record AppliedValue(UUID valueId, String role) {}
}
