package dev.prism.core.document.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentType;
import dev.prism.core.document.DocumentView;
import dev.prism.core.document.internal.ContentInspector.Inspection;
import dev.prism.core.document.internal.ContentInspector.RejectedContent;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.security.CurrentActor;
import dev.prism.core.platform.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
class DefaultDocumentService implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentService.class);
    private static final int MAX_FILENAME_LENGTH = 200;

    private final DocumentRepository documents;
    private final DocumentStore store;
    private final ContentInspector inspector;
    private final AuditRecorder audit;
    private final PrismProperties.Documents limits;

    DefaultDocumentService(
            DocumentRepository documents,
            DocumentStore store,
            ContentInspector inspector,
            AuditRecorder audit,
            PrismProperties properties) {
        this.documents = documents;
        this.store = store;
        this.inspector = inspector;
        this.audit = audit;
        this.limits = properties.documents();
    }

    private interface Spool {
        void writeTo(Path target) throws IOException;
    }

    @Override
    public DocumentView store(DocumentOwner owner, DocumentType type, MultipartFile file) {
        return ingest(owner, type, file.getOriginalFilename(), file::transferTo, null);
    }

    @Override
    public DocumentView store(DocumentOwner owner, DocumentType type, Path file) {
        return ingest(owner, type, file.getFileName().toString(),
                target -> Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING), null);
    }

    @Override
    public DocumentView replace(DocumentOwner owner, UUID documentId, MultipartFile file) {
        DocumentEntity current = requireActive(owner, documentId);
        // Retired first and flushed: the database allows only one active copy of a file per owner.
        current.changeStatus(DocumentView.Status.SUPERSEDED);
        documents.flush();
        DocumentView replacement =
                ingest(owner, current.documentType(), file.getOriginalFilename(), file::transferTo, current.id());
        audit.record(entry(owner, AuditAction.DOCUMENT_REPLACED, AuditOutcome.SUCCESS)
                .target("DOCUMENT", replacement.id())
                .detail(AuditDetail.DOCUMENT_ID, current.id()));
        return replacement;
    }

    @Override
    public void remove(DocumentOwner owner, UUID documentId) {
        DocumentEntity document = requireActive(owner, documentId);
        document.changeStatus(DocumentView.Status.REMOVED);
        audit.record(entry(owner, AuditAction.DOCUMENT_REMOVED, AuditOutcome.SUCCESS).target("DOCUMENT", document.id()));
    }

    @Override
    public List<DocumentView> listFor(DocumentOwner.Type ownerType, UUID ownerId) {
        return documents.findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(ownerType, ownerId).stream()
                .map(DocumentEntity::toView)
                .toList();
    }

    @Override
    public java.util.Optional<OwnedDocument> find(UUID documentId) {
        return documents.findById(documentId)
                .map(document -> new OwnedDocument(document.ownerType(), document.ownerId(), document.toView()));
    }

    @Override
    public StoredContent readForProcessing(DocumentOwner.Type ownerType, UUID ownerId, UUID documentId) {
        DocumentEntity document = documents.findById(documentId)
                .filter(found -> found.ownerType() == ownerType && found.ownerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        try {
            return new StoredContent(document.toView(), Files.readAllBytes(store.locate(document.storageKey())));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read stored document", exception);
        }
    }

    @Override
    public long countActive(DocumentOwner.Type ownerType, UUID ownerId) {
        return listFor(ownerType, ownerId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .count();
    }

    private DocumentView ingest(DocumentOwner owner, DocumentType type, String rawName, Spool spool, UUID supersedesId) {
        Path spooled = null;
        try {
            spooled = Files.createTempFile("prism-upload", ".tmp");
            spool.writeTo(spooled);
            return ingestSpooled(owner, type, sanitizeFilename(rawName), spooled, supersedesId);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not spool upload", exception);
        } finally {
            deleteQuietly(spooled);
        }
    }

    private DocumentView ingestSpooled(DocumentOwner owner, DocumentType type, String filename, Path file, UUID supersedesId)
            throws IOException {
        boolean claimDocument = owner.type() == DocumentOwner.Type.CLAIM;
        long size = Files.size(file);
        long maxBytes = claimDocument ? limits.maxClaimDocumentBytes() : limits.maxPolicyDocumentBytes();
        if (size > maxBytes) {
            throw reject(owner, type, HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "This file is larger than the %d MB limit.".formatted(maxBytes / (1024 * 1024)));
        }

        Inspection inspection;
        try {
            inspection = inspector.inspect(file);
        } catch (RejectedContent rejected) {
            throw reject(owner, type, HttpStatus.UNPROCESSABLE_CONTENT, rejected.code(), rejected.getMessage());
        }
        int maxPages = claimDocument ? limits.maxClaimDocumentPages() : limits.maxPolicyDocumentPages();
        if (inspection.pageCount() > maxPages) {
            throw reject(owner, type, HttpStatus.UNPROCESSABLE_CONTENT, "too_many_pages",
                    "This document has more than the %d page limit.".formatted(maxPages));
        }
        if (!claimDocument && !"application/pdf".equals(inspection.mediaType())) {
            throw reject(owner, type, HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_type", "Policy wordings must be PDF files.");
        }

        byte[] hash = sha256(file);
        if (documents.existsByOwnerTypeAndOwnerIdAndStatusAndSha256(owner.type(), owner.id(), DocumentView.Status.ACTIVE, hash)) {
            throw reject(owner, type, HttpStatus.CONFLICT, "duplicate_document", "This file has already been uploaded here.");
        }

        String storageKey = "%s/%s".formatted(owner.type().name().toLowerCase(), UUID.randomUUID());
        store.put(storageKey, file);
        removeStoredFileOnRollback(storageKey);

        DocumentEntity saved = documents.save(new DocumentEntity(
                owner, type, filename, inspection.mediaType(), size, inspection.pageCount(), hash, storageKey,
                supersedesId, CurrentActor.require().userId()));
        audit.record(entry(owner, AuditAction.DOCUMENT_UPLOADED, AuditOutcome.SUCCESS)
                .target("DOCUMENT", saved.id())
                .detail(AuditDetail.DOCUMENT_TYPE, type));
        return saved.toView();
    }

    private void removeStoredFileOnRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        store.delete(storageKey);
                    } catch (IOException exception) {
                        log.warn("Could not remove orphaned document {}", storageKey);
                    }
                }
            }
        });
    }

    private ApiException reject(DocumentOwner owner, DocumentType type, HttpStatus status, String code, String message) {
        // Independent of the caller's transaction, which rolls back when this exception is thrown.
        audit.recordIndependently(entry(owner, AuditAction.DOCUMENT_REJECTED, AuditOutcome.FAILURE)
                .detail(AuditDetail.DOCUMENT_TYPE, type)
                .detail(AuditDetail.REASON, code));
        return new ApiException(status, code, message);
    }

    private DocumentEntity requireActive(DocumentOwner owner, UUID documentId) {
        return documents.findById(documentId)
                .filter(document -> document.ownerType() == owner.type() && document.ownerId().equals(owner.id()))
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Document not found."));
    }

    private static AuditEntry entry(DocumentOwner owner, AuditAction action, AuditOutcome outcome) {
        return AuditEntry.of(owner.auditStream(), action, outcome);
    }

    /** Keeps only a display name. It is never used to build a path. */
    static String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            return "document";
        }
        return name.length() <= MAX_FILENAME_LENGTH ? name : name.substring(name.length() - MAX_FILENAME_LENGTH);
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                log.warn("Could not delete temporary upload");
            }
        }
    }
}
