package dev.prism.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class UserAdministrationTest extends IntegrationTest {

    private MockHttpServletResponse setRoles(Client admin, Object userId, List<String> roles) throws Exception {
        return admin.send(MockMvcRequestBuilders.put("/api/v1/users/" + userId + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("roles", roles))));
    }

    @Test
    void roleChangeEndsTheUsersSessionAndAppliesOnNextSignIn() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Client user = signedInAs(Role.REVIEWER);

        assertThat(setRoles(admin, user.userId, List.of("AUDITOR")).getStatus()).isEqualTo(200);

        assertThat(user.getPath("/api/v1/auth/me").getStatus()).isEqualTo(401);
        Client renewed = anonymous();
        renewed.signIn(user.email, PASSWORD);
        assertThat(renewed.getPath("/api/v1/audit/events").getStatus()).isEqualTo(200);
    }

    @Test
    void deactivationEndsSessionsAndBlocksSignIn() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        Client user = signedInAs(Role.REVIEWER);

        assertThat(admin.postEmpty("/api/v1/users/" + user.userId + "/deactivation").getStatus()).isEqualTo(200);

        assertThat(user.getPath("/api/v1/auth/me").getStatus()).isEqualTo(401);
        assertThat(anonymous().signIn(user.email, PASSWORD).getStatus()).isEqualTo(401);

        assertThat(admin.postEmpty("/api/v1/users/" + user.userId + "/reactivation").getStatus()).isEqualTo(200);
        assertThat(anonymous().signIn(user.email, PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void administratorsCannotRemoveTheirOwnAccess() throws Exception {
        Client admin = signedInAs(Role.ADMIN);

        MockHttpServletResponse demote = setRoles(admin, admin.userId, List.of("REVIEWER"));
        MockHttpServletResponse deactivate = admin.postEmpty("/api/v1/users/" + admin.userId + "/deactivation");

        assertThat(demote.getStatus()).isEqualTo(409);
        assertThat(admin.bodyOf(demote).get("code").asString()).isEqualTo("self_lockout");
        assertThat(deactivate.getStatus()).isEqualTo(409);
    }

    @Test
    void theLastActiveAdministratorCannotBeRemoved() throws Exception {
        Client remaining = signedInAs(Role.ADMIN);
        Client other = signedInAs(Role.ADMIN);
        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE core.users SET status = 'DEACTIVATED' WHERE id NOT IN ('"
                    + remaining.userId + "', '" + other.userId + "') AND id IN "
                    + "(SELECT user_id FROM core.user_roles WHERE role = 'ADMIN')");
            statement.execute("UPDATE core.users SET status = 'DEACTIVATED' WHERE id = '" + other.userId + "'");
        }

        // Only "remaining" is active, so removing it through a stale session of "other" must fail.
        MockHttpServletResponse response = other.postEmpty("/api/v1/users/" + remaining.userId + "/deactivation");

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(other.bodyOf(response).get("code").asString()).isEqualTo("last_administrator");

        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE core.users SET status = 'ACTIVE' WHERE id = '" + other.userId + "'");
        }
    }

    @Test
    void listIsPaginatedAndSearchable() throws Exception {
        Client admin = signedInAs(Role.ADMIN);
        signedInAs(Role.REVIEWER);
        signedInAs(Role.REVIEWER);

        var first = admin.bodyOf(admin.getPath("/api/v1/users?limit=2"));
        assertThat(first.get("items")).hasSize(2);
        String cursor = first.get("nextCursor").asString();

        var second = admin.bodyOf(admin.getPath("/api/v1/users?limit=2&cursor=" + cursor));
        assertThat(second.get("items").get(0).get("id").asString())
                .isNotEqualTo(first.get("items").get(1).get("id").asString());

        var found = admin.bodyOf(admin.getPath("/api/v1/users?search=" + admin.email.toUpperCase()));
        assertThat(found.get("items")).hasSize(1);
    }

    @Test
    void passwordHashesAreNeverReturned() throws Exception {
        Client admin = signedInAs(Role.ADMIN);

        String body = admin.getPath("/api/v1/users").getContentAsString();

        assertThat(body).doesNotContain("argon2").doesNotContainIgnoringCase("passwordHash");
    }

    @Test
    void passwordsAreStoredAsArgon2id() throws Exception {
        Client user = signedInAs(Role.REVIEWER);

        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT password_hash FROM core.users WHERE id = '" + user.userId + "'")) {
            rows.next();
            assertThat(rows.getString(1)).startsWith("$argon2id$").doesNotContain(PASSWORD);
        }
    }
}
