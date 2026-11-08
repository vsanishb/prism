package dev.prism.core.risk;

/**
 * A rule as reviewers see it, including when it found nothing.
 *
 * @param checks what the rule looks for, in plain language
 */
public record RuleDescription(
        String id, int version, Category category, IndicatorKind kind, Severity severity, String title, String checks) {}
