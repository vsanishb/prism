package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.PolicyExtraction.Reference;
import dev.prism.core.aiclient.PolicyExtraction.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Assembled policy knowledge, before storage. */
record Assembly(List<Provision> provisions, List<Relation> relations, List<Finding> findings) {

    /** A provision joined from every batch that saw it. Mutable only while assembling. */
    static final class Provision {
        final String key;
        final String type;
        final String title;
        String text;
        int pageStart;
        int pageEnd;
        final String quote;
        final List<BigDecimal> region;
        final String grounding;
        boolean textVerified;
        final String parentKey;
        final Set<String> exceptions = new LinkedHashSet<>();
        final List<Reference> references = new ArrayList<>();
        final List<Value> values = new ArrayList<>();
        boolean continuesOnNextPage;

        Provision(dev.prism.core.aiclient.PolicyExtraction.Provision source) {
            this.key = source.key();
            this.type = source.type();
            this.title = source.title();
            this.text = source.text();
            this.pageStart = source.page();
            this.pageEnd = source.page();
            this.quote = source.quote();
            this.region = source.region();
            this.grounding = source.grounding();
            this.textVerified = source.textVerified();
            this.parentKey = source.parentKey();
            this.continuesOnNextPage = source.continuesOnNextPage();
            absorb(source);
        }

        /** Adds what another reading of this provision found. Text is joined, never replaced by a shorter copy. */
        void absorb(dev.prism.core.aiclient.PolicyExtraction.Provision other) {
            exceptions.addAll(other.exceptions());
            for (Reference reference : other.references()) {
                if (references.stream().noneMatch(existing -> existing.targetKey().equals(reference.targetKey())
                        && existing.relation().equals(reference.relation()))) {
                    references.add(reference);
                }
            }
            for (Value value : other.values()) {
                if (values.stream().noneMatch(existing -> sameValue(existing, value))) {
                    values.add(value);
                }
            }
            pageStart = Math.min(pageStart, other.page());
            pageEnd = Math.max(pageEnd, other.page());
        }

        private static boolean sameValue(Value first, Value second) {
            return first.kind().equals(second.kind())
                    && first.rawValue().equals(second.rawValue())
                    && java.util.Objects.equals(first.quote(), second.quote());
        }
    }

    record Relation(Provision from, String targetKey, Provision to, String relation, String origin, boolean statedInText) {}

    enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    /** Points at a provision, and at one of its values by position, when the finding is about a value. */
    record Finding(
            String code, Severity severity, Provision provision, Value value, String expectedValue, String actualValue) {

        static Finding of(String code, Severity severity, Provision provision) {
            return new Finding(code, severity, provision, null, null, null);
        }
    }
}
