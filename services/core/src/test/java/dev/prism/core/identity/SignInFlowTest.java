package dev.prism.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class SignInFlowTest extends IntegrationTest {

    @Test
    void signsInAndReturnsTheCurrentUser() throws Exception {
        Client client = signedInAs(Role.ADMIN);

        MockHttpServletResponse me = client.getPath("/api/v1/auth/me");

        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(client.bodyOf(me).get("email").asString()).isEqualTo(client.email);
        assertThat(client.bodyOf(me).get("permissions").toString()).contains("USERS_MANAGE");
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSite() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);

        MockHttpServletResponse response = anonymous().signIn(email, PASSWORD);

        assertThat(response.getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header)
                        .startsWith("prism_session=")
                        .contains("HttpOnly")
                        .contains("SameSite=Lax"));
    }

    @Test
    void emailMatchingIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);

        assertThat(anonymous().signIn(email.toUpperCase(), PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void failuresAreIndistinguishable() throws Exception {
        String known = uniqueEmail();
        identity.createUser(known, Role.REVIEWER, PASSWORD);
        String inactive = uniqueEmail();
        identity.deactivateDirectly(identity.createUser(inactive, Role.REVIEWER, PASSWORD));

        MockHttpServletResponse wrongPassword = anonymous().signIn(known, "wrong-" + PASSWORD);
        MockHttpServletResponse unknownAccount = anonymous().signIn(uniqueEmail(), PASSWORD);
        MockHttpServletResponse deactivated = anonymous().signIn(inactive, PASSWORD);

        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(unknownAccount.getStatus()).isEqualTo(401);
        assertThat(deactivated.getStatus()).isEqualTo(401);
        assertThat(unknownAccount.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
        assertThat(deactivated.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
    }

    @Test
    void repeatedFailuresBlockTheAccountEvenForTheCorrectPassword() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);
        Client attacker = anonymous();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(attacker.signIn(email, "wrong-" + attempt + PASSWORD).getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse blocked = anonymous().signIn(email, PASSWORD);

        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void successfulSignInClearsEarlierFailures() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);
        Client client = anonymous();
        for (int attempt = 0; attempt < 4; attempt++) {
            client.signIn(email, "wrong-" + attempt + PASSWORD);
        }
        assertThat(client.signIn(email, PASSWORD).getStatus()).isEqualTo(200);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(anonymous().signIn(email, "wrong-" + attempt + PASSWORD).getStatus()).isEqualTo(401);
        }
    }

    @Test
    void signInRequiresACsrfToken() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);

        MockHttpServletResponse response = anonymous()
                .postWithoutCsrf("/api/v1/auth/sign-in", Map.of("email", email, "password", PASSWORD));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void stateChangesRequireACsrfToken() throws Exception {
        Client admin = signedInAs(Role.ADMIN);

        MockHttpServletResponse response = admin.postWithoutCsrf(
                "/api/v1/invitations",
                Map.of("email", uniqueEmail(), "fullName", "Synthetic Person", "roles", new String[] {"REVIEWER"}));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(admin.bodyOf(response).get("code").asString()).isEqualTo("csrf_invalid");
    }

    @Test
    void signInIssuesANewSessionIdAndCsrfToken() throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, Role.REVIEWER, PASSWORD);
        Client client = anonymous();
        client.getPath("/api/v1/auth/csrf");
        String csrfBefore = client.cookie("XSRF-TOKEN");

        client.signIn(email, PASSWORD);
        String sessionAfterFirst = client.cookie("prism_session");
        String csrfAfter = client.cookie("XSRF-TOKEN");
        client.signIn(email, PASSWORD);

        assertThat(sessionAfterFirst).isNotBlank();
        assertThat(csrfAfter).isNotEqualTo(csrfBefore);
        assertThat(client.cookie("prism_session")).isNotBlank().isNotEqualTo(sessionAfterFirst);
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        Client client = signedInAs(Role.REVIEWER);
        String session = client.cookie("prism_session");
        assertThat(session).isNotBlank();
        assertThat(client.getPath("/api/v1/auth/me").getStatus()).isEqualTo(200);

        assertThat(client.postEmpty("/api/v1/auth/sign-out").getStatus()).isEqualTo(204);

        Client replay = anonymous();
        MockHttpServletResponse response = replay.send(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("prism_session", session)));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsUnknownFieldsAndMissingValues() throws Exception {
        Client client = anonymous();

        assertThat(client.postJson("/api/v1/auth/sign-in", Map.of("email", "", "password", "x")).getStatus())
                .isEqualTo(400);
        assertThat(client.postJson(
                                "/api/v1/auth/sign-in",
                                Map.of("email", "a@b.test", "password", "x", "admin", UUID.randomUUID().toString()))
                        .getStatus())
                .isEqualTo(400);
    }
}
