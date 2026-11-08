package dev.prism.core.risk;

/**
 * The vocabulary rules may use. A statistical anomaly comes only from the detectors, and confirmed
 * fraud only from an investigator: the system has no way to express either from a rule.
 */
public enum IndicatorKind {
    /** Documents, or documents and the entered details, disagree. */
    DATA_INCONSISTENCY,
    /** A pattern that needs attention. */
    RISK_INDICATOR,
    /** A pattern that may warrant investigation. Never a finding of fraud. */
    FRAUD_INDICATOR
}
