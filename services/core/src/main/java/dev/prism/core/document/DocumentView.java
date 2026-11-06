package dev.prism.core.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentView(
        UUID id,
        DocumentType documentType,
        Status status,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        int pageCount,
        UUID supersedesId,
        UUID uploadedBy,
        Instant uploadedAt) {

    public enum Status {
        ACTIVE,
        SUPERSEDED,
        REMOVED
    }
}
