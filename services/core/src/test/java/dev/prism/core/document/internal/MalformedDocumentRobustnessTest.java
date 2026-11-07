package dev.prism.core.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.management.ThreadMXBean;
import dev.prism.core.document.internal.ContentInspector.RejectedContent;
import dev.prism.core.platform.config.PrismProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Core parses every upload with PDFBox and ImageIO before storing it. Malformed files may be
 * refused or accepted, but must finish within time and memory bounds and never escape with an
 * unexpected error. Fuzzing and an independent review remain M9 work.
 */
class MalformedDocumentRobustnessTest {

    private static final Duration TIME_BOUND = Duration.ofSeconds(20);
    private static final long ALLOCATION_BOUND_BYTES = 1_500_000_000L;
    private static final Path VALID_PDF = Path.of("../../test-data/claims/appendectomy/hospital-bill.pdf");

    @TempDir
    static Path directory;

    private static ContentInspector inspector;
    private static PageRenderer renderer;

    @BeforeAll
    static void setUp() {
        PrismProperties properties = mock(PrismProperties.class, RETURNS_DEEP_STUBS);
        when(properties.documents().maxImagePixels()).thenReturn(60_000_000L);
        when(properties.documents().maxRenderedEdgePixels()).thenReturn(1800);
        inspector = new ContentInspector(properties);
        renderer = new PageRenderer(properties);
    }

    record Sample(String name, byte[] content) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Sample> samples() throws IOException {
        byte[] valid = Files.readAllBytes(VALID_PDF);
        Map<String, byte[]> samples = new LinkedHashMap<>();
        samples.put("header only", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        samples.put("cut in half", Arrays.copyOf(valid, valid.length / 2));
        samples.put("cut at the end", Arrays.copyOf(valid, valid.length - 40));
        samples.put("random after header", withHeader(random(4096, 1)));
        samples.put("circular page tree", ascii("""
                %PDF-1.7
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [2 0 R] /Count 1000000 /Parent 2 0 R >> endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """));
        samples.put("self-referencing stream length", ascii("""
                %PDF-1.7
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
                3 0 obj << /Type /Page /Parent 2 0 R /Contents 4 0 R >> endobj
                4 0 obj << /Length 4 0 R >> stream
                BT ET
                endstream endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """));
        samples.put("deep nesting", ascii("%PDF-1.7\n1 0 obj " + "<<".repeat(20_000) + " endobj\n%%EOF\n"));
        samples.put("huge page count claim", ascii("""
                %PDF-1.7
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [] /Count 2147483647 >> endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """));
        samples.put("corrupted bytes", corrupted(valid, 2));
        samples.put("heavily corrupted bytes", corrupted(valid, 3, 200));
        samples.put("png claiming a huge size", pngClaiming(100_000, 100_000));
        samples.put("png with garbage", concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, random(512, 4)));
        samples.put("tiff with garbage", concat(new byte[] {'I', 'I', 0x2A, 0x00}, random(512, 5)));
        samples.put("jpeg with garbage", concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, random(512, 6)));
        return samples.entrySet().stream().map(entry -> new Sample(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest
    @MethodSource("samples")
    void inspectionFinishesWithinBoundsWithOnlyExpectedOutcomes(Sample sample) throws Exception {
        Path file = write(sample);
        assertBounded(() -> {
            try {
                inspector.inspect(file);
            } catch (RejectedContent refused) {
                assertThat(refused.code()).isNotBlank();
            }
        });
    }

    @ParameterizedTest
    @MethodSource("samples")
    void acceptedFilesRenderWithinBoundsOrFailCleanly(Sample sample) throws Exception {
        Path file = write(sample);
        ContentInspector.Inspection inspection;
        try {
            inspection = inspector.inspect(file);
        } catch (RejectedContent refused) {
            return;
        }
        assertBounded(() -> {
            try {
                renderer.renderPng(file, inspection.mediaType(), 0);
            } catch (IOException expected) {
                assertThat(expected).isNotNull();
            }
        });
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void assertBounded(Check check) {
        assertTimeoutPreemptively(TIME_BOUND, () -> {
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().threadId();
            long before = threads.getThreadAllocatedBytes(thread);
            check.run();
            assertThat(threads.getThreadAllocatedBytes(thread) - before).isLessThan(ALLOCATION_BOUND_BYTES);
        });
    }

    private static Path write(Sample sample) throws IOException {
        return Files.write(Files.createTempFile(directory, "sample", ".bin"), sample.content());
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] withHeader(byte[] body) {
        return concat("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), body);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] corrupted(byte[] source, long seed) {
        return corrupted(source, seed, 20);
    }

    /** Leaves the header intact, so that the file still reaches the parser. */
    private static byte[] corrupted(byte[] source, long seed, int changes) {
        byte[] copy = source.clone();
        Random random = new Random(seed);
        for (int change = 0; change < changes; change++) {
            copy[16 + random.nextInt(copy.length - 16)] = (byte) random.nextInt(256);
        }
        return copy;
    }

    private static byte[] pngClaiming(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2);
        writeChunk(output, "IHDR", header.array());
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[1000]);
        deflater.finish();
        byte[] compressed = new byte[200];
        int size = deflater.deflate(compressed);
        deflater.end();
        writeChunk(output, "IDAT", Arrays.copyOf(compressed, size));
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        output.write(ByteBuffer.allocate(4).putInt(data.length).array());
        output.write(name);
        output.write(data);
        output.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
