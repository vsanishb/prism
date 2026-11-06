package dev.prism.core.identity.internal;

import dev.prism.core.platform.web.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Follows NIST 800-63B: checks length and known-weak values, with no composition rules. */
@Component
class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private final Set<String> blocklist;

    PasswordPolicy() {
        ClassPathResource resource = new ClassPathResource("security/common-passwords.txt");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            blocklist = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            throw new UncheckedIOException("Password blocklist could not be loaded", exception);
        }
    }

    void validate(String password, String email) {
        if (password.length() < MIN_LENGTH) {
            throw reject("Use at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH) {
            throw reject("Use no more than " + MAX_LENGTH + " characters.");
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        if (blocklist.contains(normalized) || isRepetitive(normalized)) {
            throw reject("This password is too common. Choose something harder to guess.");
        }
        String localPart = email.substring(0, email.indexOf('@'));
        if (localPart.length() >= 4 && normalized.contains(localPart)) {
            throw reject("Your password must not contain your email name.");
        }
    }

    private static boolean isRepetitive(String password) {
        return password.chars().distinct().count() < 5;
    }

    private static ApiException reject(String message) {
        return ApiException.invalid("weak_password", message);
    }
}
