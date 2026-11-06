package dev.prism.core.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.prism.core.platform.config.PrismProperties;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemDocumentStoreTest {

    private static FilesystemDocumentStore storeAt(Path root) throws Exception {
        PrismProperties properties = mock(PrismProperties.class, RETURNS_DEEP_STUBS);
        when(properties.documents().storagePath()).thenReturn(root.toString());
        return new FilesystemDocumentStore(properties);
    }

    @Test
    void storesReadsAndDeletes(@TempDir Path directory) throws Exception {
        FilesystemDocumentStore store = storeAt(directory.resolve("root"));
        Path source = Files.writeString(directory.resolve("source"), "synthetic");

        store.put("claim/one", source);
        assertThat(Files.readString(store.locate("claim/one"))).isEqualTo("synthetic");

        store.delete("claim/one");
        assertThatThrownBy(() -> store.locate("claim/one")).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void leavesNoPartialFiles(@TempDir Path directory) throws Exception {
        Path root = directory.resolve("root");
        FilesystemDocumentStore store = storeAt(root);

        store.put("claim/one", Files.writeString(directory.resolve("source"), "synthetic"));

        try (var files = Files.list(root.resolve("claim"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("one");
        }
    }

    @Test
    void refusesKeysThatEscapeTheRoot(@TempDir Path directory) throws Exception {
        FilesystemDocumentStore store = storeAt(directory.resolve("root"));

        assertThatThrownBy(() -> store.locate("../outside")).isInstanceOf(IllegalArgumentException.class);
    }
}
