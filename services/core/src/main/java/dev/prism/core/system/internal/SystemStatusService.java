package dev.prism.core.system.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.AiServiceClient.PingResult;
import dev.prism.core.aiclient.AiServiceClient.ProbeResult;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
class SystemStatusService {

    private final JdbcClient jdbc;
    private final AiServiceClient aiService;

    SystemStatusService(JdbcClient jdbc, AiServiceClient aiService) {
        this.jdbc = jdbc;
        this.aiService = aiService;
    }

    @PreAuthorize("hasAuthority('SYSTEM_VIEW')")
    public SystemStatus status() {
        PingResult ping = aiService.ping();
        ProbeResult probe = aiService.probeUnsignedRequest();
        List<Check> checks = List.of(
                databaseCheck(),
                new Check(
                        "ai_service_authentication",
                        "Signed call from core to AI service",
                        ping.authenticated(),
                        ping.authenticated() ? "Accepted with a valid service token" : ping.detail()),
                new Check(
                        "ai_service_rejects_unsigned",
                        "AI service refuses unsigned calls",
                        probe.rejected(),
                        probeDetail(probe)),
                new Check(
                        "knowledge_database",
                        "AI service database connection",
                        ping.knowledgeDatabaseConnected(),
                        ping.detail()));
        return new SystemStatus(Instant.now(), checks);
    }

    private Check databaseCheck() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return new Check("core_database", "Core database connection", true, "Connected");
        } catch (DataAccessException exception) {
            return new Check("core_database", "Core database connection", false, "Database is unavailable");
        }
    }

    private static String probeDetail(ProbeResult probe) {
        if (!probe.reachable()) {
            return "AI service is unreachable";
        }
        return probe.rejected() ? "Refused with status 401" : "Unexpected status " + probe.statusCode();
    }

    record Check(String id, String name, boolean healthy, String detail) {}

    record SystemStatus(Instant checkedAt, List<Check> checks) {}
}
