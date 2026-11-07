package dev.prism.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.prism.core.aiclient.AiServiceClient.PingResult;
import dev.prism.core.aiclient.AiServiceClient.ProbeResult;
import dev.prism.core.identity.Role;
import dev.prism.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class AccessControlTest extends IntegrationTest {

    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "GET /api/v1/auth/csrf",
            "POST /api/v1/auth/sign-in",
            "POST /api/v1/auth/tokens/inspect",
            "POST /api/v1/auth/tokens/redeem");

    private static final Pattern PATH_VARIABLE = Pattern.compile("[{][^}]+[}]");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** New routes are covered automatically: anything not listed above must require a session. */
    @Test
    void everyRouteRequiresASessionUnlessExplicitlyPublic() throws Exception {
        List<String> exposed = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    String route = method + " " + pattern;
                    if (PUBLIC_ROUTES.contains(route)) {
                        continue;
                    }
                    String path = PATH_VARIABLE.matcher(pattern).replaceAll(UUID.randomUUID().toString());
                    MockHttpServletResponse response = anonymous().send(
                            MockMvcRequestBuilders.request(HttpMethod.valueOf(method.name()), path));
                    if (response.getStatus() != 401) {
                        exposed.add(route + " -> " + response.getStatus());
                    }
                }
            }
        }
        assertThat(exposed).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "ADMIN, /api/v1/users, 200",
        "ADMIN, /api/v1/invitations, 200",
        "ADMIN, /api/v1/audit/events, 403",
        "AUDITOR, /api/v1/audit/events, 200",
        "AUDITOR, /api/v1/users, 403",
        "AUDITOR, /api/v1/system/status, 403",
        "REVIEWER, /api/v1/users, 403",
        "REVIEWER, /api/v1/audit/events, 403",
        "REVIEWER, /api/v1/system/status, 403",
        "POLICY_MANAGER, /api/v1/users, 403",
        "CLAIMS_PROCESSOR, /api/v1/audit/events, 403",
        "SENIOR_REVIEWER, /api/v1/invitations, 403",
        "FRAUD_INVESTIGATOR, /api/v1/users, 403"
    })
    void rolesReachOnlyTheirOwnEndpoints(Role role, String path, int expected) throws Exception {
        assertThat(signedInAs(role).getPath(path).getStatus()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void onlyAdministratorsCanInvite(Role role) throws Exception {
        MockHttpServletResponse response = signedInAs(role).postJson(
                "/api/v1/invitations",
                Map.of("email", uniqueEmail(), "fullName", "Synthetic Person", "roles", List.of("ADMIN")));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void systemStatusReportsLiveChecksToAdministrators() throws Exception {
        when(aiService.ping()).thenReturn(new PingResult(true, true, true, "Connected"));
        when(aiService.probeUnsignedRequest()).thenReturn(new ProbeResult(true, true, 401));
        when(workflowHealth.reachable()).thenReturn(true);
        Client admin = signedInAs(Role.ADMIN);

        var checks = admin.bodyOf(admin.getPath("/api/v1/system/status")).get("checks");

        assertThat(checks).hasSize(5);
        assertThat(checks.valueStream()).allSatisfy(check -> assertThat(check.get("healthy").asBoolean()).isTrue());
    }

    @Test
    void systemStatusShowsAFailureWhenTheWorkflowEngineIsDown() throws Exception {
        when(aiService.ping()).thenReturn(new PingResult(true, true, true, "Connected"));
        when(aiService.probeUnsignedRequest()).thenReturn(new ProbeResult(true, true, 401));
        when(workflowHealth.reachable()).thenReturn(false);
        Client admin = signedInAs(Role.ADMIN);

        var checks = admin.bodyOf(admin.getPath("/api/v1/system/status")).get("checks");

        assertThat(checks.get(4).get("id").asString()).isEqualTo("workflow_engine");
        assertThat(checks.get(4).get("healthy").asBoolean()).isFalse();
    }

    @Test
    void systemStatusShowsAFailureWhenTheAiServiceIsDown() throws Exception {
        when(aiService.ping()).thenReturn(new PingResult(false, false, false, "AI service is unreachable"));
        when(aiService.probeUnsignedRequest()).thenReturn(new ProbeResult(false, false, 0));
        Client admin = signedInAs(Role.ADMIN);

        MockHttpServletResponse response = admin.getPath("/api/v1/system/status");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(admin.bodyOf(response).get("checks").get(1).get("healthy").asBoolean()).isFalse();
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        MockHttpServletResponse response = anonymous().getPath("/api/v1/auth/csrf");

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void errorsAreProblemDocumentsWithoutInternals() throws Exception {
        Client admin = signedInAs(Role.ADMIN);

        MockHttpServletResponse response = admin.postEmpty("/api/v1/users/" + UUID.randomUUID() + "/deactivation");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("Exception").doesNotContain("dev.prism");
    }
}
