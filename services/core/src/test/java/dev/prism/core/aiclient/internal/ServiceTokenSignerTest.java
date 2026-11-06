package dev.prism.core.aiclient.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import dev.prism.core.platform.config.PrismProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTokenSignerTest {

    @Test
    void signsAShortLivedTokenBoundToTheRequest(@TempDir Path directory) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        Path key = directory.resolve("key.pem");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        ServiceTokenSigner signer = new ServiceTokenSigner(new PrismProperties(
                "test",
                URI.create("http://localhost"),
                new PrismProperties.Security("key", false, false, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)),
                new PrismProperties.ServiceAuth(key.toString(), "core-test", Duration.ofSeconds(60)),
                new PrismProperties.Ai(URI.create("http://localhost"), Duration.ofSeconds(1)),
                new PrismProperties.Bootstrap("", ""),
                new PrismProperties.Seed("")));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        SignedJWT token = SignedJWT.parse(signer.sign("POST /internal/v1/ping", body));

        assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(token.getHeader().getKeyID()).isEqualTo("core-test");
        var claims = token.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("prism-core");
        assertThat(claims.getAudience()).containsExactly("prism-ai");
        assertThat(claims.getSubject()).isEqualTo("POST /internal/v1/ping");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isLessThanOrEqualTo(60_000);
        assertThat(claims.getStringClaim("body_sha256"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
    }
}
