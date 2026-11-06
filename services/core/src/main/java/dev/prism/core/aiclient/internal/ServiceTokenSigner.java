package dev.prism.core.aiclient.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.prism.core.platform.config.PrismProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived ES256 tokens for calls to the AI service. Binding the body hash into the token
 * means a captured token cannot authorize a different request.
 */
@Component
class ServiceTokenSigner {

    private final ECDSASigner signer;
    private final String keyId;
    private final Duration ttl;

    ServiceTokenSigner(PrismProperties properties) throws IOException, GeneralSecurityException, JOSEException {
        this.signer = new ECDSASigner(readPrivateKey(Path.of(properties.serviceAuth().privateKeyPath())));
        this.keyId = properties.serviceAuth().keyId();
        this.ttl = properties.serviceAuth().tokenTtl();
    }

    String sign(String operation, byte[] body) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("prism-core")
                .audience("prism-ai")
                .subject(operation)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .claim("body_sha256", sha256(body))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(keyId)
                .build();
        SignedJWT token = new SignedJWT(header, claims);
        try {
            token.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign service token", exception);
        }
        return token.serialize();
    }

    private static ECPrivateKey readPrivateKey(Path path) throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
