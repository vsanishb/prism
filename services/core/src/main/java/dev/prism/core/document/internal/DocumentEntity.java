package dev.prism.core.document.internal;

import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentType;
import dev.prism.core.document.DocumentView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "documents", schema = "core")
class DocumentEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type")
    private DocumentOwner.Type ownerType;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    private DocumentView.Status status = DocumentView.Status.ACTIVE;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "page_count")
    private int pageCount;

    private byte[] sha256;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", updatable = false)
    private Instant uploadedAt = Instant.now();

    protected DocumentEntity() {}

    DocumentEntity(
            DocumentOwner owner,
            DocumentType documentType,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            int pageCount,
            byte[] sha256,
            String storageKey,
            UUID supersedesId,
            UUID uploadedBy) {
        this.ownerType = owner.type();
        this.ownerId = owner.id();
        this.documentType = documentType;
        this.originalFilename = originalFilename;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.pageCount = pageCount;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.supersedesId = supersedesId;
        this.uploadedBy = uploadedBy;
    }

    UUID id() {
        return id;
    }

    DocumentOwner.Type ownerType() {
        return ownerType;
    }

    UUID ownerId() {
        return ownerId;
    }

    DocumentType documentType() {
        return documentType;
    }

    DocumentView.Status status() {
        return status;
    }

    String mediaType() {
        return mediaType;
    }

    String originalFilename() {
        return originalFilename;
    }

    int pageCount() {
        return pageCount;
    }

    String storageKey() {
        return storageKey;
    }

    void changeStatus(DocumentView.Status newStatus) {
        this.status = newStatus;
    }

    DocumentView toView() {
        return new DocumentView(
                id, documentType, status, originalFilename, mediaType, sizeBytes, pageCount, supersedesId, uploadedBy, uploadedAt);
    }
}
