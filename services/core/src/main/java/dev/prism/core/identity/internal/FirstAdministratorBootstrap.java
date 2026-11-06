package dev.prism.core.identity.internal;

import dev.prism.core.identity.internal.AccountTokenService.IssuedLink;
import dev.prism.core.platform.config.PrismProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the first administrator's invitation on an empty system, so that Prism needs no default
 * account. The link goes to a file rather than the log, because logs must never contain secrets.
 */
@Component
class FirstAdministratorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdministratorBootstrap.class);

    private final UserRepository users;
    private final AccountTokenService tokens;
    private final PrismProperties properties;

    FirstAdministratorBootstrap(UserRepository users, AccountTokenService tokens, PrismProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String email = properties.bootstrap().adminEmail();
        String file = properties.bootstrap().inviteFile();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(file) || users.count() > 0) {
            return;
        }
        IssuedLink invitation = tokens.inviteFirstAdministrator(email);
        Path path = Path.of(file);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, invitation.link() + System.lineSeparator());
        log.info("First administrator invitation written to {}", path);
    }
}
