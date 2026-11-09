package dev.prism.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

class InvitationFlowTest extends IntegrationTest {

    private record Invite(String email, String token, String id) {}

    private Invite invite(Client admin, Role role) throws Exception {
        String email = uniqueEmail();
        MockHttpServletResponse response = admin.postJson(
                "/api/v1/invitations",
                Map.of("email", email, "fullName", "Sneha Bhatt", "roles", List.of(role.name())));
        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode body = admin.bodyOf(response);
        String link = body.get("link").asString();
        assertThat(link).startsWith("http://localhost:3000/accept-invite#token=");
        return new Invite(email, link.substring(link.indexOf("#token=") + 7), body.get("id").asString());
    }

    private MockHttpServletResponse redeem(String token, String password) throws Exception {
        return anonymous().postJson("/api/v1/auth/tokens/redeem", Map.of("token", token, "password", password));
    }

    @Test
    void invitedUserSetsAPasswordAndSignsIn() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Invite invite = invite(admin, Role.REVIEWER);

        MockHttpServletResponse details =
                anonymous().postJson("/api/v1/auth/tokens/inspect", Map.of("token", invite.token()));
        assertThat(admin.bodyOf(details).get("email").asString()).isEqualTo(invite.email());

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(204);

        MockHttpServletResponse signIn = anonymous().signIn(invite.email(), PASSWORD);
        assertThat(signIn.getStatus()).isEqualTo(200);
        assertThat(admin.bodyOf(signIn).get("roles").toString()).contains("REVIEWER");
    }

    @Test
    void tokenWorksOnlyOnce() throws Exception {
        Invite invite = invite(signedInAs(Role.ADMIN), Role.REVIEWER);

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(204);

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(422);
    }

    @Test
    void concurrentRedemptionsCreateOneAccount() throws Exception {
        Invite invite = invite(signedInAs(Role.ADMIN), Role.REVIEWER);
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            attempts.add(() -> redeem(invite.token(), PASSWORD).getStatus());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (Future<Integer> result : executor.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsOnlyOnce(204);
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        Invite invite = invite(signedInAs(Role.ADMIN), Role.REVIEWER);
        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE core.account_tokens SET expires_at = now() - interval '1 minute' WHERE id = '"
                    + invite.id() + "'");
        }

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(422);
    }

    @Test
    void revokedInvitationIsRefused() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Invite invite = invite(admin, Role.REVIEWER);

        assertThat(admin.send(MockMvcRequestBuilders.delete("/api/v1/invitations/" + invite.id())).getStatus())
                .isEqualTo(204);

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(422);
    }

    @Test
    void reinvitingReplacesTheEarlierLink() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Invite first = invite(admin, Role.REVIEWER);

        MockHttpServletResponse second = admin.postJson(
                "/api/v1/invitations",
                Map.of("email", first.email(), "fullName", "Sneha Bhatt", "roles", List.of("AUDITOR")));

        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(redeem(first.token(), PASSWORD).getStatus()).isEqualTo(422);
    }

    @Test
    void rawTokensAreNotStored() throws Exception {
        Invite invite = invite(signedInAs(Role.ADMIN), Role.REVIEWER);

        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT encode(token_hash, 'escape') AS stored FROM core.account_tokens WHERE id = '" + invite.id() + "'")) {
            rows.next();
            assertThat(rows.getString("stored")).doesNotContain(invite.token());
        }
    }

    @Test
    void weakPasswordsAreRefusedAndTheLinkStaysUsable() throws Exception {
        Invite invite = invite(signedInAs(Role.ADMIN), Role.REVIEWER);

        for (String weak : List.of("short", "password1234", "aaaaaaaaaaaaaaaa")) {
            assertThat(redeem(invite.token(), weak).getStatus()).isEqualTo(422);
        }

        assertThat(redeem(invite.token(), PASSWORD).getStatus()).isEqualTo(204);
    }

    @Test
    void duplicateEmailIsRefused() throws Exception {
        Client admin = signedInAs(Role.ADMIN);

        MockHttpServletResponse response = admin.postJson(
                "/api/v1/invitations",
                Map.of("email", admin.email, "fullName", "Amit Chauhan", "roles", List.of("REVIEWER")));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void passwordResetReplacesThePasswordAndEndsSessions() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Client user = signedInAs(Role.REVIEWER);

        JsonNode reset = admin.bodyOf(admin.postEmpty("/api/v1/users/" + user.userId + "/password-reset"));
        String link = reset.get("link").asString();
        assertThat(link).contains("/reset-password#token=");
        String newPassword = "another-Synthetic-phrase-77";

        assertThat(redeem(link.substring(link.indexOf("#token=") + 7), newPassword).getStatus()).isEqualTo(204);

        assertThat(user.getPath("/api/v1/auth/me").getStatus()).isEqualTo(401);
        assertThat(anonymous().signIn(user.email, PASSWORD).getStatus()).isEqualTo(401);
        assertThat(anonymous().signIn(user.email, newPassword).getStatus()).isEqualTo(200);
    }
}
