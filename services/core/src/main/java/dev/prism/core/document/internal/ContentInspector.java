package dev.prism.core.document.internal;

import dev.prism.core.platform.config.PrismProperties;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

/**
 * Decides what an upload really is. The type comes from file content, never from its name or the
 * client's header, and images are measured from their headers, so that a decompression bomb is
 * refused without ever being decoded.
 */
@Component
class ContentInspector {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] TIFF_LITTLE_ENDIAN = {'I', 'I', 0x2A, 0x00};
    private static final byte[] TIFF_BIG_ENDIAN = {'M', 'M', 0x00, 0x2A};
    private static final int SIGNATURE_BYTES = 8;

    private final long maxImagePixels;

    ContentInspector(PrismProperties properties) {
        this.maxImagePixels = properties.documents().maxImagePixels();
    }

    record Inspection(String mediaType, int pageCount) {}

    /** Thrown with a message that is safe to show to the person uploading. */
    static final class RejectedContent extends Exception {
        private final String code;

        RejectedContent(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    Inspection inspect(Path file) throws RejectedContent, IOException {
        byte[] signature = readSignature(file);
        if (signature.length == 0) {
            throw new RejectedContent("empty_file", "This file is empty.");
        }
        if (startsWith(signature, PDF)) {
            return inspectPdf(file);
        }
        if (startsWith(signature, PNG)) {
            return inspectImage(file, "image/png");
        }
        if (startsWith(signature, JPEG)) {
            return inspectImage(file, "image/jpeg");
        }
        if (startsWith(signature, TIFF_LITTLE_ENDIAN) || startsWith(signature, TIFF_BIG_ENDIAN)) {
            return inspectImage(file, "image/tiff");
        }
        throw new RejectedContent(
                "unsupported_type", "This file type is not supported. Upload a PDF, PNG, JPEG, or TIFF.");
    }

    private Inspection inspectPdf(Path file) throws RejectedContent {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(file.toFile()))) {
            if (document.isEncrypted()) {
                throw passwordProtected();
            }
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new RejectedContent("malformed_file", "This PDF has no pages.");
            }
            return new Inspection("application/pdf", pages);
        } catch (InvalidPasswordException exception) {
            throw passwordProtected();
        } catch (IOException exception) {
            throw new RejectedContent("malformed_file", "This PDF is damaged and cannot be read.");
        }
    }

    private Inspection inspectImage(Path file, String mediaType) throws RejectedContent {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mediaType);
            if (input == null || !readers.hasNext()) {
                throw malformedImage();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int pages = reader.getNumImages(true);
                for (int page = 0; page < pages; page++) {
                    Dimension size = new Dimension(reader.getWidth(page), reader.getHeight(page));
                    if ((long) size.width * size.height > maxImagePixels) {
                        throw new RejectedContent("image_too_large", "This image has too many pixels to process.");
                    }
                }
                return new Inspection(mediaType, pages);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalStateException exception) {
            throw malformedImage();
        }
    }

    private static RejectedContent passwordProtected() {
        return new RejectedContent("encrypted_file", "This PDF is password protected. Remove the password and upload it again.");
    }

    private static RejectedContent malformedImage() {
        return new RejectedContent("malformed_file", "This image is damaged and cannot be read.");
    }

    private static byte[] readSignature(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return input.readNBytes(SIGNATURE_BYTES);
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(Arrays.copyOf(content, prefix.length), prefix);
    }
}
