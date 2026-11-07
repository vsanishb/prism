package dev.prism.core.policyknowledge.internal;

import dev.prism.core.document.DocumentView;
import dev.prism.core.policy.PolicyKnowledgeLifecycle.VersionState;
import dev.prism.core.policyknowledge.internal.KnowledgeView.BatchView;
import dev.prism.core.policyknowledge.internal.KnowledgeView.FindingView;
import dev.prism.core.policyknowledge.internal.KnowledgeView.ProvisionView;
import dev.prism.core.policyknowledge.internal.KnowledgeView.RelationView;
import dev.prism.core.policyknowledge.internal.KnowledgeView.ReviewView;
import dev.prism.core.policyknowledge.internal.KnowledgeView.ValueView;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads a version's current knowledge: the ingestion of its active wording. */
@Repository
class KnowledgeReader {

    private final JdbcClient jdbc;

    KnowledgeReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    KnowledgeView read(VersionState version, DocumentView wording) {
        String uploader = wording == null ? null : userName(wording.uploadedBy());
        String publisher = version.publishedBy() == null ? null : userName(version.publishedBy());
        Optional<UUID> ingestion = wording == null
                ? Optional.empty()
                : jdbc.sql("SELECT id FROM core.policy_ingestions WHERE document_id = ?")
                        .param(wording.id())
                        .query(UUID.class)
                        .optional();
        return new KnowledgeView(
                version.versionId(),
                version.versionNumber(),
                version.knowledgeStatus(),
                version.sumInsured(),
                version.currency(),
                wording == null ? null : wording.id(),
                wording == null ? null : wording.originalFilename(),
                wording == null ? null : wording.pageCount(),
                uploader,
                ingestion.map(this::batches).orElse(List.of()),
                ingestion.map(this::provisions).orElse(List.of()),
                ingestion.map(this::relations).orElse(List.of()),
                ingestion.map(this::findings).orElse(List.of()),
                List.of(),
                publisher,
                version.publishedAt(),
                version.rejectionReason());
    }

    /** A value belonging to the version's current ingestion, so that an old ingestion cannot be reviewed. */
    Optional<ValueView> value(UUID versionId, UUID valueId) {
        return jdbc.sql("""
                        SELECT v.* FROM core.policy_values v
                        JOIN core.policy_provisions p ON p.id = v.provision_id
                        JOIN core.policy_ingestions i ON i.id = p.ingestion_id
                        JOIN core.documents d ON d.id = i.document_id
                        WHERE v.id = ? AND i.version_id = ? AND d.status = 'ACTIVE'
                        """)
                .params(valueId, versionId)
                .query((row, index) -> value(row, null))
                .optional();
    }

    void insertReview(UUID valueId, String decision, String correctedValue, String note, UUID reviewer) {
        jdbc.sql("""
                        INSERT INTO core.policy_value_reviews (value_id, decision, corrected_value, note, reviewed_by)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(valueId, decision, correctedValue, note, reviewer)
                .update();
    }

    private List<BatchView> batches(UUID ingestion) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (first_page) first_page, last_page, status, detail_code
                        FROM core.policy_batch_events WHERE ingestion_id = ? ORDER BY first_page, id DESC
                        """)
                .param(ingestion)
                .query((row, index) -> new BatchView(
                        row.getInt("first_page"), row.getInt("last_page"), row.getString("status"), row.getString("detail_code")))
                .list();
    }

    private List<ProvisionView> provisions(UUID ingestion) {
        Map<UUID, ReviewView> reviews = jdbc.sql("""
                        SELECT DISTINCT ON (r.value_id) r.value_id, r.decision, r.corrected_value, r.note, r.reviewed_at,
                            u.full_name
                        FROM core.policy_value_reviews r
                        JOIN core.policy_values v ON v.id = r.value_id
                        JOIN core.policy_provisions p ON p.id = v.provision_id
                        JOIN core.users u ON u.id = r.reviewed_by
                        WHERE p.ingestion_id = ? ORDER BY r.value_id, r.id DESC
                        """)
                .param(ingestion)
                .query((row, index) -> Map.entry(
                        row.getObject("value_id", UUID.class),
                        new ReviewView(
                                row.getString("decision"),
                                row.getString("corrected_value"),
                                row.getString("note"),
                                row.getString("full_name"),
                                row.getObject("reviewed_at", OffsetDateTime.class).toInstant())))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<UUID, List<ValueView>> values = jdbc.sql("""
                        SELECT v.* FROM core.policy_values v JOIN core.policy_provisions p ON p.id = v.provision_id
                        WHERE p.ingestion_id = ? ORDER BY v.provision_id, v.position
                        """)
                .param(ingestion)
                .query((row, index) -> Map.entry(row.getObject("provision_id", UUID.class), value(row, reviews)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.sql("SELECT * FROM core.policy_provisions WHERE ingestion_id = ? ORDER BY position")
                .param(ingestion)
                .query((row, index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    return new ProvisionView(
                            id,
                            row.getString("provision_key"),
                            row.getString("type"),
                            row.getString("title"),
                            row.getString("text"),
                            row.getInt("page_start"),
                            row.getInt("page_end"),
                            row.getString("quote"),
                            region(row.getArray("region")),
                            row.getString("grounding"),
                            row.getBoolean("text_verified"),
                            row.getString("parent_key"),
                            Arrays.asList((String[]) row.getArray("exceptions").getArray()),
                            values.getOrDefault(id, List.of()));
                })
                .list();
    }

    private static ValueView value(ResultSet row, Map<UUID, ReviewView> reviews) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        return new ValueView(
                id,
                row.getString("name"),
                row.getString("kind"),
                row.getString("raw_value"),
                row.getString("value"),
                row.getString("unit"),
                row.getString("qualifier"),
                row.getString("state"),
                row.getObject("page", Integer.class),
                row.getString("quote"),
                region(row.getArray("region")),
                row.getString("grounding"),
                row.getString("problem"),
                row.getBoolean("critical"),
                reviews == null ? null : reviews.get(id));
    }

    private List<RelationView> relations(UUID ingestion) {
        return jdbc.sql("SELECT * FROM core.policy_relations WHERE ingestion_id = ? ORDER BY id")
                .param(ingestion)
                .query((row, index) -> new RelationView(
                        row.getObject("from_provision_id", UUID.class),
                        row.getString("target_key"),
                        row.getObject("to_provision_id", UUID.class),
                        row.getString("relation"),
                        row.getString("origin"),
                        row.getBoolean("stated_in_text")))
                .list();
    }

    private List<FindingView> findings(UUID ingestion) {
        return jdbc.sql("""
                        SELECT * FROM core.policy_findings WHERE ingestion_id = ?
                        ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, id
                        """)
                .param(ingestion)
                .query((row, index) -> new FindingView(
                        row.getString("code"),
                        row.getString("severity"),
                        row.getObject("provision_id", UUID.class),
                        row.getObject("value_id", UUID.class),
                        row.getString("expected_value"),
                        row.getString("actual_value")))
                .list();
    }

    private String userName(UUID userId) {
        return jdbc.sql("SELECT full_name FROM core.users WHERE id = ?").param(userId).query(String.class).optional().orElse(null);
    }

    private static List<BigDecimal> region(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((BigDecimal[]) array.getArray());
    }
}
