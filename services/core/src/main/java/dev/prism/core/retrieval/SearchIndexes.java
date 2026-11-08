package dev.prism.core.retrieval;

import java.util.UUID;

/** Whether a version can be searched. For modules that search on a claim's behalf. */
public interface SearchIndexes {

    boolean ready(UUID versionId);
}
