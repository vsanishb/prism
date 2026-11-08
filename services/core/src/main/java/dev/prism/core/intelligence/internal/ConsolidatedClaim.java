package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.DocumentExtraction.Field;
import dev.prism.core.aiclient.DocumentExtraction.LineItem;
import dev.prism.core.aiclient.DocumentExtraction.Result;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The output of consolidation and validation, before storage. */
record ConsolidatedClaim(List<Fact> facts, List<DocumentLineItem> lineItems, List<Finding> findings) {

    static final String PRESENT = "PRESENT";
    static final String MISSING = "MISSING";
    static final String UNCERTAIN = "UNCERTAIN";
    static final String CONFLICTING = "CONFLICTING";

    /** One document's stored extraction, with the type it is treated as. */
    record ExtractedDocument(UUID documentId, String declaredType, Result result) {

        /** A clear reading of the document outranks the label chosen at upload. */
        String effectiveType() {
            return "HIGH".equals(result.classification().confidence())
                    ? result.classification().documentType()
                    : declaredType;
        }
    }

    /**
     * @param documentId null for a claim-level fact
     * @param correctionId set when a reviewer's correction gave the value; the sources still show what documents said
     */
    record Fact(
            String name,
            String kind,
            boolean critical,
            UUID documentId,
            String state,
            String value,
            BigDecimal amount,
            List<Source> sources,
            UUID correctionId) {

        Fact(String name, String kind, boolean critical, UUID documentId, String state, String value, BigDecimal amount,
                List<Source> sources) {
            this(name, kind, critical, documentId, state, value, amount, sources, null);
        }
    }

    record Source(UUID documentId, Field field) {}

    record DocumentLineItem(UUID documentId, int position, LineItem item) {}

    enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    record Finding(
            String code,
            Severity severity,
            String fieldName,
            UUID documentId,
            String expectedValue,
            String actualValue) {

        static Finding of(String code, Severity severity) {
            return new Finding(code, severity, null, null, null, null);
        }
    }
}
