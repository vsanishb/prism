package dev.prism.core.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * A disposable database on a real PostgreSQL server, created once per test run. Tests log in as
 * dedicated roles that inherit the runtime roles' grants, so that they exercise the real
 * permissions without changing the passwords of the roles used for local development.
 */
public final class TestDatabase {

    public static final String CORE_LOGIN = "prism_core_test_login";
    public static final String AI_LOGIN = "prism_ai_test_login";

    private static final TestDatabase INSTANCE = create();

    private final String adminUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String name;
    private final String password;

    private TestDatabase(String adminUrl, String adminUser, String adminPassword, String name, String password) {
        this.adminUrl = adminUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.name = name;
        this.password = password;
    }

    public static TestDatabase instance() {
        return INSTANCE;
    }

    public String url() {
        return adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + name;
    }

    public String password() {
        return password;
    }

    public Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(url(), adminUser, adminPassword);
    }

    public Connection connectAs(String login) throws SQLException {
        return DriverManager.getConnection(url(), login, password);
    }

    private static TestDatabase create() {
        Map<String, String> env = environment();
        String url = require(env, "PRISM_TEST_ADMIN_DB_URL");
        String user = require(env, "PRISM_TEST_ADMIN_DB_USER");
        String adminPassword = require(env, "PRISM_TEST_ADMIN_DB_PASSWORD");
        HexFormat hex = HexFormat.of();
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String password = hex.formatHex(random);
        String name = "prism_test_" + password.substring(0, 12);

        try (Connection connection = DriverManager.getConnection(url, user, adminPassword);
                Statement statement = connection.createStatement()) {
            for (String role : List.of("prism_core_app", "prism_ai_app", "prism_audit_reader")) {
                statement.execute(createRoleIfMissing(role, "NOLOGIN"));
            }
            statement.execute(createRoleIfMissing(CORE_LOGIN, "LOGIN"));
            statement.execute(createRoleIfMissing(AI_LOGIN, "LOGIN"));
            statement.execute("ALTER ROLE %s PASSWORD '%s'".formatted(CORE_LOGIN, password));
            statement.execute("ALTER ROLE %s PASSWORD '%s'".formatted(AI_LOGIN, password));
            statement.execute("GRANT prism_core_app TO " + CORE_LOGIN);
            statement.execute("GRANT prism_ai_app TO " + AI_LOGIN);
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the test database", exception);
        }

        TestDatabase database = new TestDatabase(url, user, adminPassword, name, password);
        Flyway.configure()
                .dataSource(database.url(), user, adminPassword)
                .locations("filesystem:../../database/migrations")
                .defaultSchema("core")
                .schemas("core")
                .createSchemas(true)
                .load()
                .migrate();
        Runtime.getRuntime().addShutdownHook(new Thread(database::drop));
        return database;
    }

    private void drop() {
        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException exception) {
            System.err.println("Could not drop test database " + name);
        }
    }

    private static String createRoleIfMissing(String role, String option) {
        return """
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%1$s') THEN
                        CREATE ROLE %1$s %2$s;
                    END IF;
                END $$
                """.formatted(role, option);
    }

    private static String require(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required. Run the setup command, or set it in CI.");
        }
        return value;
    }

    private static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of("../../.env");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    int separator = line.indexOf('=');
                    if (separator > 0 && !line.startsWith("#")) {
                        values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read .env", exception);
            }
        }
        values.putAll(System.getenv());
        return values;
    }
}
