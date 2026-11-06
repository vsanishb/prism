package dev.prism.core.platform.security;

import dev.prism.core.platform.config.PrismProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the client address for throttling. X-Forwarded-For is honoured only when configured,
 * and only its last entry is used: that entry was written by our own proxy, while anything before
 * it could have been supplied by the client.
 */
@Component
public class ClientAddressResolver {

    private final boolean forwardedHeaderTrusted;

    public ClientAddressResolver(PrismProperties properties) {
        this.forwardedHeaderTrusted = properties.security().forwardedHeaderTrusted();
    }

    public String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwardedHeaderTrusted && forwarded != null && !forwarded.isBlank()) {
            return forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
        }
        return request.getRemoteAddr();
    }
}
