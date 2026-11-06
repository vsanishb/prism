package dev.prism.core.document;

import java.util.UUID;

/**
 * Lets owning modules decide who may read their documents, so that this module needs no knowledge
 * of claims or policies. Implementations must throw when the current user has no access.
 */
public interface DocumentAccessGuard {

    DocumentOwner.Type ownerType();

    /** Returns the owner for audit purposes, or throws if the current user may not read it. */
    DocumentOwner requireReadable(UUID ownerId);
}
