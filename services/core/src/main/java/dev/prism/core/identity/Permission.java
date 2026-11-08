package dev.prism.core.identity;

/** Code checks permissions, never roles. Each milestone adds the permissions it needs. */
public enum Permission {
    USERS_MANAGE,
    AUDIT_READ,
    SYSTEM_VIEW,
    POLICY_READ,
    POLICY_MANAGE,
    CLAIM_READ,
    CLAIM_MANAGE,
    /** Work tasks in the review queue: standard claims within the reviewer authority limit. */
    REVIEW_QUEUE,
    /** Work tasks in the senior queue: escalated, high-value, and post-investigation claims. */
    SENIOR_REVIEW_QUEUE,
    /** Work tasks in the investigation queue, and record investigation findings. */
    INVESTIGATION_QUEUE,
    /** Record an authorized outcome on a task in a queue the user may work. */
    OUTCOME_RECORD,
    /** Revise a recorded outcome, with a new superseding record. */
    OUTCOME_REVISE
}
