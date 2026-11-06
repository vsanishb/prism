package dev.prism.core.document;

import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Document storage for owning modules. These methods perform no authorization: callers must check
 * permissions and owner state first, and must call within a transaction.
 */
public interface DocumentService {

    DocumentView store(DocumentOwner owner, DocumentType type, MultipartFile file);

    /** Stores a file read from disk. Used by the synthetic seeder. */
    DocumentView store(DocumentOwner owner, DocumentType type, java.nio.file.Path file);

    DocumentView replace(DocumentOwner owner, UUID documentId, MultipartFile file);

    void remove(DocumentOwner owner, UUID documentId);

    List<DocumentView> listFor(DocumentOwner.Type ownerType, UUID ownerId);

    long countActive(DocumentOwner.Type ownerType, UUID ownerId);
}
