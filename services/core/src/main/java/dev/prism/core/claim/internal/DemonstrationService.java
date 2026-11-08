package dev.prism.core.claim.internal;

import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seeded risk demonstrations (D-088), for the local environment only. Submitting a scenario uses
 * the normal claim submission: nothing here changes how a claim is processed.
 */
@Service
class DemonstrationService {

    private static final List<String> SCENARIOS = List.of("SEVERE", "CLEAN", "DISGUISED");
    /** An earlier claim counts once processing has finished, wherever it went next. */
    private static final Set<String> PROCESSED = Set.of("IN_REVIEW", "ESCALATED", "DECIDED");

    private final JdbcClient jdbc;
    private final PrismProperties properties;

    DemonstrationService(JdbcClient jdbc, PrismProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    record ClaimReference(UUID id, String claimNumber, String status) {}

    /** @param ready whether every earlier claim it is compared with has been processed */
    record Scenario(String scenario, ClaimReference claim, List<ClaimReference> earlierClaims, boolean ready) {}

    private record Row(String scenario, String role, ClaimReference claim) {}

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional(readOnly = true)
    public List<Scenario> scenarios() {
        if (!properties.isLocal()) {
            throw ApiException.notFound("Demonstrations are available only in the local environment.");
        }
        List<Row> rows = jdbc.sql("""
                        SELECT d.scenario, d.role, c.id, c.claim_number, c.status FROM core.demonstration_claims d
                        JOIN core.claims c ON c.id = d.claim_id
                        ORDER BY d.scenario, d.role, d.position
                        """)
                .query((row, index) -> new Row(row.getString("scenario"), row.getString("role"), new ClaimReference(
                        row.getObject("id", UUID.class), row.getString("claim_number"), row.getString("status"))))
                .list();
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        for (String key : SCENARIOS) {
            ClaimReference claim = rows.stream()
                    .filter(row -> key.equals(row.scenario()) && "SCENARIO".equals(row.role()))
                    .map(Row::claim)
                    .findFirst()
                    .orElse(null);
            if (claim == null) {
                continue;
            }
            List<ClaimReference> earlier = new ArrayList<>(rows.stream()
                    .filter(row -> key.equals(row.scenario()) && "EARLIER".equals(row.role()))
                    .map(Row::claim)
                    .toList());
            boolean ready = earlier.stream().allMatch(reference -> PROCESSED.contains(reference.status()));
            scenarios.put(key, new Scenario(key, claim, List.copyOf(earlier), ready));
        }
        return List.copyOf(scenarios.values());
    }
}
