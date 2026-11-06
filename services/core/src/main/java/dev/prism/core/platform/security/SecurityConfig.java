package dev.prism.core.platform.security;

import dev.prism.core.platform.config.PrismProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Routes reachable without a session. Everything else is denied by default. */
    public static final String[] PUBLIC_POST_ROUTES = {
        "/api/v1/auth/sign-in", "/api/v1/auth/tokens/inspect", "/api/v1/auth/tokens/redeem"
    };

    public static final String[] PUBLIC_GET_ROUTES = {"/api/v1/auth/csrf", "/actuator/health/**"};

    /**
     * Declared explicitly because Spring Session writes its own cookie, and the server.servlet
     * cookie properties do not reliably reach it. A test asserts these attributes on the real
     * response. The __Host- prefix makes browsers refuse the cookie unless it is Secure and
     * host-only, so it is used whenever the cookie is Secure.
     */
    @Bean
    CookieSerializer sessionCookieSerializer(PrismProperties properties) {
        boolean secure = properties.security().cookieSecure();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(secure ? "__Host-prism_session" : "prism_session");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PrismProperties properties,
            ProblemAuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler,
            SecurityContextRepository securityContextRepository)
            throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ROUTES).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ROUTES).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterAfter(new AbsoluteSessionTimeoutFilter(properties), SecurityContextHolderFilter.class);
        return http.build();
    }
}
