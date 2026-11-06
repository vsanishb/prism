package dev.prism.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.platform.config.PrismProperties;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteSessionTimeoutFilterTest {

    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(new PrismProperties(
            "test",
            URI.create("http://localhost"),
            new PrismProperties.Security("key", false, false, Duration.ofHours(12), Duration.ofHours(1), Duration.ofHours(1)),
            new PrismProperties.ServiceAuth("path", "id", Duration.ofMinutes(1)),
            new PrismProperties.Ai(URI.create("http://localhost"), Duration.ofSeconds(1)),
            new PrismProperties.Bootstrap("", ""),
            new PrismProperties.Seed("")));

    private static HttpSession sessionAged(Duration age) {
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return System.currentTimeMillis() - age.toMillis();
            }
        };
    }

    private static boolean invalidatedAfterFilter(AbsoluteSessionTimeoutFilter filter, HttpSession session)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return ((MockHttpSession) session).isInvalid();
    }

    @Test
    void keepsAYoungSession() throws Exception {
        assertThat(invalidatedAfterFilter(filter, sessionAged(Duration.ofHours(11)))).isFalse();
    }

    @Test
    void endsASessionPastTheAbsoluteLimitEvenIfActive() throws Exception {
        assertThat(invalidatedAfterFilter(filter, sessionAged(Duration.ofHours(13)))).isTrue();
    }
}
