package dev.prism.core.intelligence.internal;

import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.document.DocumentOwner;
import dev.prism.core.document.DocumentService;
import dev.prism.core.document.DocumentView;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the current facts of a claim, each with the sources that support it. */
@Service
class ClaimFactsService {

    private final ClaimDirectory claims;
    private final DocumentService documents;
    private final JdbcClient jdbc;

    ClaimFactsService(ClaimDirectory claims, DocumentService documents, JdbcClient jdbc) {
        this.claims = claims;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    record ClaimFacts(
            Integer sequence,
            Instant consolidatedAt,
            boolean complete,
            List<FactView> facts,
            List<LineItemView> lineItems,
            List<FindingView> findings,
            List<DocumentRef> documents) {}

    record FactView(
            UUID id,
            String name,
            String kind,
            boolean critical,
            UUID documentId,
            String state,
            String value,
            List<SourceView> sources,
            CorrectionView correction) {}

    /** Set when a reviewer's correction gave the value. The sources still show what the documents said. */
    record CorrectionView(UUID id, String reason, String correctedBy, Instant correctedAt) {}

    record SourceView(
            UUID documentId,
            String state,
            String confidence,
            String rawValue,
            String value,
            Integer page,
            String quote,
            List<BigDecimal> region,
            String grounding,
            String reason) {}

    record LineItemView(
            UUID documentId,
            int position,
            String description,
            String quantity,
            String unitPrice,
            BigDecimal amount,
            String rawAmount,
            String state,
            int page,
            String quote,
            List<BigDecimal> region,
            String grounding) {}

    record FindingView(
            String code, String severity, String fieldName, UUID documentId, String expectedValue, String actualValue) {}

    record DocumentRef(UUID id, String originalFilename, String documentType, String classifiedType, int pageCount) {}

    private record Header(UUID id, int sequence, Instant createdAt, boolean complete) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public ClaimFacts read(UUID claimId) {
        claims.visible(claimId);
        List<DocumentRef> refs = documentRefs(claimId);
        Optional<Header> latest = jdbc.sql("""
                        SELECT id, sequence, created_at, complete FROM core.claim_consolidations
                        WHERE claim_id = ? ORDER BY sequence DESC LIMIT 1
                        """)
                .param(claimId)
                .query((row, index) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getInt("sequence"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getBoolean("complete")))
                .optional();
        if (latest.isEmpty()) {
            return new ClaimFacts(null, null, false, List.of(), List.of(), List.of(), refs);
        }
        Header header = latest.get();
        return new ClaimFacts(
                header.sequence(),
                header.createdAt(),
                header.complete(),
                facts(header.id()),
                lineItems(header.id()),
                findings(header.id()),
                refs);
    }

    private List<DocumentRef> documentRefs(UUID claimId) {
        Map<UUID, String> classified = jdbc.sql("""
                        SELECT DISTINCT ON (document_id) document_id, classified_type
                        FROM core.document_extractions WHERE claim_id = ? ORDER BY document_id, id DESC
                        """)
                .param(claimId)
                .query((row, index) -> Map.entry(row.getObject("document_id", UUID.class), row.getString("classified_type")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return documents.listFor(DocumentOwner.Type.CLAIM, claimId).stream()
                .filter(document -> document.status() == DocumentView.Status.ACTIVE)
                .map(document -> new DocumentRef(
                        document.id(),
                        document.originalFilename(),
                        document.documentType().name(),
                        classified.get(document.id()),
                        document.pageCount()))
                .toList();
    }

    private List<FactView> facts(UUID consolidation) {
        Map<UUID, List<SourceView>> sources = jdbc.sql("""
                        SELECT s.* FROM core.claim_fact_sources s JOIN core.claim_facts f ON f.id = s.fact_id
                        WHERE f.consolidation_id = ? ORDER BY s.id
                        """)
                .param(consolidation)
                .query((row, index) -> Map.entry(row.getObject("fact_id", UUID.class), source(row)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.sql("""
                        SELECT f.*, c.reason AS correction_reason, u.full_name AS corrected_by, c.created_at AS corrected_at
                        FROM core.claim_facts f
                        LEFT JOIN core.fact_corrections c ON c.id = f.correction_id
                        LEFT JOIN core.users u ON u.id = c.corrected_by
                        WHERE f.consolidation_id = ? ORDER BY f.id
                        """)
                .param(consolidation)
                .query((row, index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    return new FactView(
                            id,
                            row.getString("field_name"),
                            row.getString("kind"),
                            row.getBoolean("critical"),
                            row.getObject("document_id", UUID.class),
                            row.getString("state"),
                            row.getString("value"),
                            sources.getOrDefault(id, List.of()),
                            row.getObject("correction_id") == null ? null : new CorrectionView(
                                    row.getObject("correction_id", UUID.class),
                                    row.getString("correction_reason"),
                                    row.getString("corrected_by"),
                                    row.getObject("corrected_at", OffsetDateTime.class).toInstant()));
                })
                .list();
    }

    private static SourceView source(ResultSet row) throws SQLException {
        return new SourceView(
                row.getObject("document_id", UUID.class),
                row.getString("state"),
                row.getString("confidence"),
                row.getString("raw_value"),
                row.getString("value"),
                row.getObject("page", Integer.class),
                row.getString("quote"),
                region(row.getArray("region")),
                row.getString("grounding"),
                row.getString("reason"));
    }

    private List<LineItemView> lineItems(UUID consolidation) {
        return jdbc.sql("""
                        SELECT * FROM core.claim_line_items WHERE consolidation_id = ?
                        ORDER BY document_id, position
                        """)
                .param(consolidation)
                .query((row, index) -> new LineItemView(
                        row.getObject("document_id", UUID.class),
                        row.getInt("position"),
                        row.getString("description"),
                        row.getString("quantity"),
                        row.getString("unit_price"),
                        row.getBigDecimal("amount"),
                        row.getString("raw_amount"),
                        row.getString("state"),
                        row.getInt("page"),
                        row.getString("quote"),
                        region(row.getArray("region")),
                        row.getString("grounding")))
                .list();
    }

    private List<FindingView> findings(UUID consolidation) {
        return jdbc.sql("""
                        SELECT * FROM core.validation_findings WHERE consolidation_id = ?
                        ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, id
                        """)
                .param(consolidation)
                .query((row, index) -> new FindingView(
                        row.getString("code"),
                        row.getString("severity"),
                        row.getString("field_name"),
                        row.getObject("document_id", UUID.class),
                        row.getString("expected_value"),
                        row.getString("actual_value")))
                .list();
    }

    private static List<BigDecimal> region(Array array) throws SQLException {
        return array == null ? null : Arrays.asList((BigDecimal[]) array.getArray());
    }
}
