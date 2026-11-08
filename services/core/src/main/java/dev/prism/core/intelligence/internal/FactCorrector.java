package dev.prism.core.intelligence.internal;

import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.FactCorrectionStore.StoredCorrection;
import java.util.List;
import java.util.Objects;

/**
 * Applies reviewers' corrections to freshly consolidated facts. Pure: a correction replaces the value
 * and makes it PRESENT, and keeps the document sources, so the screen still shows what each document
 * said. A correction for a fact this consolidation no longer has, such as one on a removed document,
 * does not apply.
 */
final class FactCorrector {

    private FactCorrector() {}

    static List<Fact> apply(List<Fact> facts, List<StoredCorrection> corrections) {
        return facts.stream()
                .map(fact -> corrections.stream()
                        .filter(correction -> correction.fieldName().equals(fact.name())
                                && Objects.equals(correction.documentId(), fact.documentId()))
                        .findFirst()
                        .map(correction -> new Fact(fact.name(), fact.kind(), fact.critical(), fact.documentId(),
                                ConsolidatedClaim.PRESENT, correction.value(), correction.amount(), fact.sources(), correction.id()))
                        .orElse(fact))
                .toList();
    }
}
