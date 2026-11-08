package dev.prism.core.risk;

/** Where a signal comes from. Each category's points are capped, so one noisy area cannot dominate. */
public enum Category {
    CONSISTENCY,
    FINANCIAL,
    DUPLICATE,
    DOCUMENT,
    MISSING_INFORMATION,
    STATISTICAL
}
