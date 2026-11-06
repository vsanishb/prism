package dev.prism.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prism.core.identity.Role;
import dev.prism.core.support.IntegrationTest;
import dev.prism.core.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

class AuditIntegrityTest extends IntegrationTest {

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TransactionTemplate transactions;

    private static AuditEntry entry(String stream) {
        return AuditEntry.of(stream, AuditAction.SIGN_OUT, AuditOutcome.SUCCESS).target("TEST", "synthetic");
    }

    private static long count(String stream) throws SQLException {
        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM core.audit_events WHERE stream = '" + stream + "'")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private JsonNode verify() throws Exception {
        Client auditor = signedInAs(Role.AUDITOR);
        return auditor.bodyOf(auditor.postEmpty("/api/v1/audit/verification"));
    }

    @Test
    void runtimeRoleCannotChangeOrDeleteEvents() throws Exception {
        recorder.recordIndependently(entry("immutability-" + UUID.randomUUID()));

        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            for (String sql : List.of(
                    "UPDATE core.audit_events SET action = 'FORGED'",
                    "DELETE FROM core.audit_events",
                    "TRUNCATE core.audit_events")) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }
    }

    @Test
    void triggerStopsTheOwnerRoleToo() throws Exception {
        recorder.recordIndependently(entry("trigger-" + UUID.randomUUID()));

        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement()) {
            for (String sql : List.of(
                    "UPDATE core.audit_events SET action = 'FORGED'",
                    "DELETE FROM core.audit_events",
                    "TRUNCATE core.audit_events")) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("append-only");
            }
        }
    }

    @Test
    void auditReaderRoleCanOnlyRead() throws Exception {
        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT has_table_privilege('prism_audit_reader', 'core.audit_events', 'SELECT'),
                               has_table_privilege('prism_audit_reader', 'core.audit_events', 'INSERT'),
                               has_table_privilege('prism_audit_reader', 'core.users', 'SELECT')
                        """)) {
            rows.next();
            assertThat(rows.getBoolean(1)).isTrue();
            assertThat(rows.getBoolean(2)).isFalse();
            assertThat(rows.getBoolean(3)).isFalse();
        }
    }

    @Test
    void aiServiceRoleCannotReadCoreData() throws Exception {
        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.AI_LOGIN);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM core.users"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void runtimeRoleHasNoDdlRights() throws Exception {
        try (Connection connection = TestDatabase.instance().connectAs(TestDatabase.CORE_LOGIN);
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE core.intruder (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void eventRollsBackWithTheBusinessTransaction() throws Exception {
        String stream = "rollback-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    recorder.record(entry(stream));
                    throw new IllegalArgumentException("business failure");
                }))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(count(stream)).isZero();
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> recorder.record(entry("no-transaction")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void independentEventSurvivesARollback() throws Exception {
        String stream = "independent-" + UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    recorder.recordIndependently(entry(stream));
                    throw new IllegalArgumentException("business failure");
                }))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(count(stream)).isEqualTo(1);
    }

    @Test
    void concurrentWritersKeepOneValidChain() throws Exception {
        String stream = "concurrent-" + UUID.randomUUID();
        List<Callable<Void>> writers = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            writers.add(() -> {
                for (int event = 0; event < 5; event++) {
                    recorder.recordIndependently(entry(stream));
                }
                return null;
            });
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (Future<Void> result : executor.invokeAll(writers)) {
                result.get();
            }
        }

        assertThat(count(stream)).isEqualTo(40);
        assertThat(verify().get("valid").asBoolean()).isTrue();
    }

    @Test
    void sensitiveActionsAreRecorded() throws Exception {
        Client reviewer = signedInAs(Role.REVIEWER);
        reviewer.getPath("/api/v1/users");
        anonymous().signIn(reviewer.email, "wrong-" + PASSWORD);

        Client auditor = signedInAs(Role.AUDITOR);
        JsonNode denied = auditor.bodyOf(
                auditor.getPath("/api/v1/audit/events?action=ACCESS_DENIED&actorId=" + reviewer.userId));
        JsonNode failures = auditor.bodyOf(auditor.getPath("/api/v1/audit/events?action=SIGN_IN&outcome=FAILURE"));

        assertThat(denied.get("items")).hasSize(1);
        assertThat(denied.get("items").get(0).get("details").get("REQUEST_PATH").asString())
                .isEqualTo("GET /api/v1/users");
        assertThat(failures.get("items")).isNotEmpty();
    }

    @Test
    void failedSignInEventsContainNoEmailOrPassword() throws Exception {
        String email = uniqueEmail();
        anonymous().signIn(email, "wrong-" + PASSWORD);

        Client auditor = signedInAs(Role.AUDITOR);
        String events = auditor.getPath("/api/v1/audit/events?action=SIGN_IN&outcome=FAILURE").getContentAsString();

        assertThat(events).doesNotContain(email).doesNotContain(PASSWORD);
    }

    /** Runs last among chain checks in effect: the row is restored before the test ends. */
    @Test
    void verificationFindsATamperedEvent() throws Exception {
        String stream = "tamper-" + UUID.randomUUID();
        recorder.recordIndependently(entry(stream));
        recorder.recordIndependently(entry(stream));

        try (Connection connection = TestDatabase.instance().connectAsOwner();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE core.audit_events DISABLE TRIGGER audit_events_block_row_change");
            statement.execute("UPDATE core.audit_events SET target_id = 'forged' WHERE stream = '" + stream + "' AND sequence = 2");
            try {
                JsonNode result = verify();
                assertThat(result.get("valid").asBoolean()).isFalse();
                assertThat(result.get("firstBreak").get("stream").asString()).isEqualTo(stream);
                assertThat(result.get("firstBreak").get("sequence").asLong()).isEqualTo(2);
            } finally {
                statement.execute("UPDATE core.audit_events SET target_id = 'synthetic' WHERE stream = '" + stream + "' AND sequence = 2");
                statement.execute("ALTER TABLE core.audit_events ENABLE TRIGGER audit_events_block_row_change");
            }
        }
        assertThat(verify().get("valid").asBoolean()).isTrue();
    }
}
