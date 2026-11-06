package dev.prism.core.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.prism.core.document.internal.ContentInspector.RejectedContent;
import dev.prism.core.platform.config.PrismProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContentInspectorTest {

    private static ContentInspector withPixelLimit(long pixels) {
        PrismProperties properties = mock(PrismProperties.class, RETURNS_DEEP_STUBS);
        when(properties.documents().maxImagePixels()).thenReturn(pixels);
        return new ContentInspector(properties);
    }

    @Test
    void measuresImagesFromHeadersAndRefusesTooManyPixels() {
        Path scan = Path.of("../../test-data/claims/scanned/prescription.png");

        assertThatThrownBy(() -> withPixelLimit(1000).inspect(scan))
                .isInstanceOfSatisfying(RejectedContent.class, rejected -> assertThat(rejected.code()).isEqualTo("image_too_large"));
    }

    @Test
    void acceptsAnImageWithinThePixelLimit() throws Exception {
        Path scan = Path.of("../../test-data/claims/scanned/prescription.png");

        assertThat(withPixelLimit(10_000_000).inspect(scan).mediaType()).isEqualTo("image/png");
    }
}
