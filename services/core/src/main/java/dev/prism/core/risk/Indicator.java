package dev.prism.core.risk;

import java.util.List;

/**
 * One rule's finding. The explanation is plain language without names, so that it can be shown
 * anywhere and summarized by a model. Names and values, where needed, are in the evidence.
 */
public record Indicator(
        String ruleId,
        int ruleVersion,
        Category category,
        IndicatorKind kind,
        Severity severity,
        String title,
        String explanation,
        List<Evidence> evidence) {}
