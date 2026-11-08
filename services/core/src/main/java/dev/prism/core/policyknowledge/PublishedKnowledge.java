package dev.prism.core.policyknowledge;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A published version's knowledge, for search. Nothing is available before publication. */
public interface PublishedKnowledge {

    /** Empty unless the version exists and is published. Performs no authorization. */
    Optional<Snapshot> snapshot(UUID versionId);

    record Snapshot(
            UUID versionId,
            UUID policyId,
            UUID documentId,
            List<Provision> provisions,
            List<Relation> relations,
            List<Value> values) {}

    record Provision(
            UUID id,
            String key,
            String type,
            String title,
            String text,
            int pageStart,
            int pageEnd,
            String parentKey,
            String quote,
            List<BigDecimal> region) {}

    /**
     * A numeric value as published: the correction where a person made one, otherwise the value a
     * person confirmed. Every such value is reviewed before publication (D-030).
     */
    record Value(UUID id, UUID provisionId, String name, String kind, String value, String unit, String qualifier) {}

    /** Only relations whose target was found in the wording. */
    record Relation(UUID fromProvisionId, UUID toProvisionId, String relation) {}
}
