package dev.prism.core.platform.security;

import dev.prism.core.platform.config.PrismProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ends sessions at a fixed age. The idle timeout alone would let an active session live forever. */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Duration absoluteTimeout;

    public AbsoluteSessionTimeoutFilter(PrismProperties properties) {
        this.absoluteTimeout = properties.security().absoluteSessionTimeout();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && System.currentTimeMillis() - session.getCreationTime() > absoluteTimeout.toMillis()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
