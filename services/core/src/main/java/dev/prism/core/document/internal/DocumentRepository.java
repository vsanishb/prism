package dev.prism.core.document.internal;

import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(DocumentOwner.Type ownerType, UUID ownerId);

    boolean existsByOwnerTypeAndOwnerIdAndStatusAndSha256(
            DocumentOwner.Type ownerType, UUID ownerId, DocumentView.Status status, byte[] sha256);
}
