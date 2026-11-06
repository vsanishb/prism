package dev.prism.core.document.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.document.DocumentAccessGuard;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentView;
import dev.prism.core.platform.web.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Every read goes through the owner's guard, and every read of content is audited. */
@Service
class DocumentReadService {

    private final DocumentRepository documents;
    private final DocumentStore store;
    private final PageRenderer renderer;
    private final AuditRecorder audit;
    private final Map<DocumentOwner.Type, DocumentAccessGuard> guards = new EnumMap<>(DocumentOwner.Type.class);

    DocumentReadService(
            DocumentRepository documents,
            DocumentStore store,
            PageRenderer renderer,
            AuditRecorder audit,
            List<DocumentAccessGuard> accessGuards) {
        this.documents = documents;
        this.store = store;
        this.renderer = renderer;
        this.audit = audit;
        accessGuards.forEach(guard -> guards.put(guard.ownerType(), guard));
    }

    record Content(Path path, String mediaType, String filename) {}

    DocumentView details(UUID documentId) {
        Authorized authorized = authorize(documentId);
        return authorized.document().toView();
    }

    byte[] renderPage(UUID documentId, int pageNumber) {
        Authorized authorized = authorize(documentId);
        DocumentEntity document = authorized.document();
        if (pageNumber < 1 || pageNumber > document.pageCount()) {
            throw ApiException.notFound("Page not found.");
        }
        try {
            byte[] png = renderer.renderPng(store.locate(document.storageKey()), document.mediaType(), pageNumber - 1);
            audit.recordIndependently(AuditEntry.of(
                            authorized.owner().auditStream(), AuditAction.DOCUMENT_VIEWED, AuditOutcome.SUCCESS)
                    .target("DOCUMENT", document.id())
                    .detail(AuditDetail.PAGE, pageNumber));
            return png;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not render document page", exception);
        }
    }

    Content download(UUID documentId) {
        Authorized authorized = authorize(documentId);
        DocumentEntity document = authorized.document();
        try {
            Path path = store.locate(document.storageKey());
            audit.recordIndependently(AuditEntry.of(
                            authorized.owner().auditStream(), AuditAction.DOCUMENT_DOWNLOADED, AuditOutcome.SUCCESS)
                    .target("DOCUMENT", document.id()));
            return new Content(path, document.mediaType(), document.originalFilename());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read stored document", exception);
        }
    }

    private Authorized authorize(UUID documentId) {
        DocumentEntity document = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document not found."));
        DocumentAccessGuard guard = guards.get(document.ownerType());
        if (guard == null) {
            throw new IllegalStateException("No access guard for " + document.ownerType());
        }
        return new Authorized(document, guard.requireReadable(document.ownerId()));
    }

    private record Authorized(DocumentEntity document, DocumentOwner owner) {}
}
