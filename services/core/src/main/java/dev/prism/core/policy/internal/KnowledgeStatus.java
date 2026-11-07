package dev.prism.core.policy.internal;

/** Where a version's policy knowledge stands. Published and rejected versions are closed for good. */
enum KnowledgeStatus {
    NO_WORDING,
    PROCESSING,
    IN_REVIEW,
    PUBLISHED,
    REJECTED;

    boolean closed() {
        return this == PUBLISHED;
    }
}
