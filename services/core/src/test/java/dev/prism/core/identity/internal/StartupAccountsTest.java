package dev.prism.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.identity.internal.AccountTokenService.IssuedLink;
import dev.prism.core.platform.config.PrismProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class StartupAccountsTest {

    private static PrismProperties properties(String environment, String adminEmail, String inviteFile, String seedFile) {
        return new PrismProperties(
                environment,
                URI.create("http://localhost"),
                "INR",
                new PrismProperties.Security("key", false, false, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)),
                new PrismProperties.ServiceAuth("path", "id", Duration.ofMinutes(1)),
                new PrismProperties.Documents("store", 1000, 1000, 10, 10, 1000, 100),
                new PrismProperties.Ai(URI.create("http://localhost"), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new PrismProperties.Temporal(false, "localhost:1", "default", Duration.ofDays(1), 1, Duration.ofSeconds(1)),
                new PrismProperties.Bootstrap(adminEmail, inviteFile),
                new PrismProperties.Seed(seedFile, ""));
    }

    @Test
    void emptySystemGetsAFirstAdministratorInvitationInAFile(@TempDir Path directory) throws Exception {
        UserRepository users = mock(UserRepository.class);
        AccountTokenService tokens = mock(AccountTokenService.class);
        Path file = directory.resolve("nested/invitation.txt");
        when(users.count()).thenReturn(0L);
        when(tokens.inviteFirstAdministrator("first@synthetic.prism.test"))
                .thenReturn(new IssuedLink(UUID.randomUUID(), "http://localhost/accept-invite#token=abc", Instant.now()));

        new FirstAdministratorBootstrap(users, tokens, properties("test", "first@synthetic.prism.test", file.toString(), ""))
                .run(null);

        assertThat(Files.readString(file)).contains("#token=abc");
    }

    @Test
    void existingUsersPreventABootstrapInvitation(@TempDir Path directory) throws Exception {
        UserRepository users = mock(UserRepository.class);
        AccountTokenService tokens = mock(AccountTokenService.class);
        Path file = directory.resolve("invitation.txt");
        when(users.count()).thenReturn(1L);

        new FirstAdministratorBootstrap(users, tokens, properties("test", "first@synthetic.prism.test", file.toString(), ""))
                .run(null);

        verify(tokens, never()).inviteFirstAdministrator(anyString());
        assertThat(file).doesNotExist();
    }

    @Test
    void noBootstrapEmailMeansNoInvitation(@TempDir Path directory) throws Exception {
        AccountTokenService tokens = mock(AccountTokenService.class);

        new FirstAdministratorBootstrap(
                        mock(UserRepository.class), tokens, properties("test", "", directory.resolve("x").toString(), ""))
                .run(null);

        verify(tokens, never()).inviteFirstAdministrator(anyString());
    }

    @Test
    void syntheticUsersCanOnlyBeSeededLocally() {
        SyntheticUserSeeder seeder = new SyntheticUserSeeder(
                mock(UserRepository.class),
                mock(PasswordEncoder.class),
                mock(AuditRecorder.class),
                mock(TransactionTemplate.class),
                properties("production", "", "", "seed.md"));

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }
}
