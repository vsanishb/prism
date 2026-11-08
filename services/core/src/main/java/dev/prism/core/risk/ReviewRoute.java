package dev.prism.core.risk;

/** Who should review the claim. Every claim reaches a person, whatever its band. */
public enum ReviewRoute {
    STANDARD_REVIEW,
    /** A senior reviewer or investigator. */
    SENIOR_REVIEW
}
