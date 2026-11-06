package dev.prism.core.document.internal;

import dev.prism.core.platform.config.PrismProperties;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/** Local development storage. Deployed environments use an S3-compatible store (M9). */
@Component
class FilesystemDocumentStore implements DocumentStore {

    private final Path root;

    FilesystemDocumentStore(PrismProperties properties) throws IOException {
        this.root = Path.of(properties.documents().storagePath()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public void put(String key, Path source) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        // Copied beside the target first, so that a crash cannot leave a partial file under the key.
        Path partial = Files.createTempFile(target.getParent(), "upload", ".partial");
        try {
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Override
    public Path locate(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new FileNotFoundException("Stored document is missing: " + key);
        }
        return path;
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the document root");
        }
        return path;
    }
}
