package dev.prism.core.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

public final class TestKeys {

    private TestKeys() {}

    /** Writes a throwaway P-256 private key in the PEM format the application reads. */
    public static Path writeSigningKey() throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String encoded = Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        Path file = Files.createTempFile("prism-test-key", ".pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        file.toFile().deleteOnExit();
        return file;
    }
}
