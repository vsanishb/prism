package dev.prism.core.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Application settings. Validation fails startup rather than letting a bad value surface later. */
@Validated
@ConfigurationProperties("prism")
public record PrismProperties(
        @NotBlank String environment,
        @NotNull URI webBaseUrl,
        @NotBlank String defaultCurrency,
        @Valid @NotNull Security security,
        @Valid @NotNull ServiceAuth serviceAuth,
        @Valid @NotNull Documents documents,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Temporal temporal,
        @Valid @NotNull Bootstrap bootstrap,
        @Valid @NotNull Seed seed) {

    public boolean isLocal() {
        return "local".equals(environment);
    }

    public record Security(
            @NotBlank String throttleHmacKey,
            boolean cookieSecure,
            boolean forwardedHeaderTrusted,
            @NotNull Duration absoluteSessionTimeout,
            @NotNull Duration invitationValidity,
            @NotNull Duration passwordResetValidity) {}

    public record ServiceAuth(
            @NotBlank String privateKeyPath, @NotBlank String keyId, @NotNull Duration tokenTtl) {}

    public record Ai(@NotNull URI baseUrl, @NotNull Duration timeout, @NotNull Duration extractionTimeout) {}

    public record Temporal(
            boolean enabled,
            @NotBlank String target,
            @NotBlank String namespace,
            @NotNull Duration informationWait,
            @Positive int maxConcurrentExtractions,
            @NotNull Duration relayInterval) {}

    public record Bootstrap(String adminEmail, String inviteFile) {}

    public record Seed(String credentialsFile, String documentsPath) {}

    public record Documents(
            @NotBlank String storagePath,
            @Positive long maxClaimDocumentBytes,
            @Positive long maxPolicyDocumentBytes,
            @Positive int maxClaimDocumentPages,
            @Positive int maxPolicyDocumentPages,
            @Positive long maxImagePixels,
            @Positive int maxRenderedEdgePixels) {}
}
