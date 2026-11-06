package dev.prism.core.document.internal;

import dev.prism.core.platform.config.PrismProperties;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders any stored document page to PNG. Rendering here keeps untrusted PDFs out of the browser
 * and gives every format, including TIFF, one display path. Rendering is CPU and memory heavy, so
 * concurrency and output size are bounded.
 */
@Component
class PageRenderer {

    private static final int MAX_CONCURRENT_RENDERS = 3;
    private static final String PDF = "application/pdf";

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_RENDERS);
    private final int maxEdgePixels;

    PageRenderer(PrismProperties properties) {
        this.maxEdgePixels = properties.documents().maxRenderedEdgePixels();
    }

    byte[] renderPng(Path file, String mediaType, int pageIndex) throws IOException {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to render", exception);
        }
        try {
            BufferedImage image = PDF.equals(mediaType) ? renderPdf(file, pageIndex) : renderImage(file, mediaType, pageIndex);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } finally {
            permits.release();
        }
    }

    private BufferedImage renderPdf(Path file, int pageIndex) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(file.toFile()))) {
            PDRectangle box = document.getPage(pageIndex).getCropBox();
            float scale = maxEdgePixels / Math.max(box.getWidth(), box.getHeight());
            return new PDFRenderer(document).renderImage(pageIndex, scale, ImageType.RGB);
        }
    }

    private BufferedImage renderImage(Path file, String mediaType, int pageIndex) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByMIMEType(mediaType).next();
            try {
                reader.setInput(input);
                return fit(reader.read(pageIndex));
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage fit(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxEdgePixels) {
            return source;
        }
        double scale = (double) maxEdgePixels / longest;
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }
}
