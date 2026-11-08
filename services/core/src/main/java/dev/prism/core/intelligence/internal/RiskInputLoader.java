package dev.prism.core.intelligence.internal;

import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RiskInput.Fact;
import dev.prism.core.risk.RiskInput.ItemKey;
import dev.prism.core.risk.RiskInput.LineItem;
import dev.prism.core.risk.RiskInput.OtherBill;
import dev.prism.core.risk.RiskInput.OtherClaim;
import dev.prism.core.risk.RiskInput.SharedFile;
import dev.prism.core.risk.RiskInput.Source;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Loads what risk rules read, from core's own records. Cross-claim matches are found here, in the
 * database (D-013): only the matching signal and the other claim's ID and number leave this class,
 * never that claim's documents or facts. Drafts and withdrawn claims are not compared against.
 */
@Repository
class RiskInputLoader {

    /** Wide enough for the similar-claim and prior-claim windows, with room either side. */
    private static final int HISTORY_DAYS = 400;

    private static final String OPEN_CLAIM = "c.status NOT IN ('DRAFT', 'WITHDRAWN')";

    private final JdbcClient jdbc;

    RiskInputLoader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    RiskInput load(ClaimRecord claim, UUID consolidationId, VersionRef version, LocalDate coverStart) {
        List<RiskInput.Document> documents = documents(claim.id());
        List<LineItem> lineItems = lineItems(consolidationId);
        return new RiskInput(
                new RiskInput.ClaimDetails(claim.id(), claim.type(), claim.patientName(), claim.hospitalName(),
                        claim.admissionDate(), claim.dischargeDate(), claim.claimedAmount(), claim.currency()),
                new RiskInput.PolicyDetails(version.sumInsured(), version.currency(), coverStart),
                documents,
                facts(consolidationId),
                lineItems,
                findings(consolidationId),
                new RiskInput.History(
                        sharedFiles(claim.id()),
                        otherBills(claim.id(), consolidationId, lineItems),
                        otherClaims(claim)));
    }

    /** Active documents with an extraction, as the type they are treated as: a confident reading, else the declared type. */
    private List<RiskInput.Document> documents(UUID claimId) {
        return jdbc.sql("""
                        SELECT d.id,
                            CASE WHEN e.classification_confidence = 'HIGH' THEN e.classified_type ELSE d.document_type END AS type
                        FROM core.documents d
                        JOIN LATERAL (
                            SELECT classified_type, classification_confidence FROM core.document_extractions
                            WHERE document_id = d.id ORDER BY id DESC LIMIT 1
                        ) e ON true
                        WHERE d.owner_type = 'CLAIM' AND d.owner_id = ? AND d.status = 'ACTIVE'
                        ORDER BY d.uploaded_at, d.id
                        """)
                .param(claimId)
                .query((row, index) -> new RiskInput.Document(row.getObject("id", UUID.class), row.getString("type")))
                .list();
    }

    private List<Fact> facts(UUID consolidationId) {
        Map<UUID, List<Source>> sources = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.fact_id, s.document_id, s.page, s.value FROM core.claim_fact_sources s
                        JOIN core.claim_facts f ON f.id = s.fact_id
                        WHERE f.consolidation_id = ? ORDER BY s.id
                        """)
                .param(consolidationId)
                .query((row, index) -> {
                    sources.computeIfAbsent(row.getObject("fact_id", UUID.class), key -> new ArrayList<>()).add(new Source(
                            row.getObject("document_id", UUID.class), (Integer) row.getObject("page"), row.getString("value")));
                    return null;
                })
                .list();
        return jdbc.sql("""
                        SELECT id, field_name, state, value, amount, document_id FROM core.claim_facts
                        WHERE consolidation_id = ? ORDER BY field_name, document_id NULLS FIRST
                        """)
                .param(consolidationId)
                .query((row, index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    return new Fact(id, row.getString("field_name"), row.getString("state"), row.getString("value"),
                            row.getBigDecimal("amount"), row.getObject("document_id", UUID.class),
                            List.copyOf(sources.getOrDefault(id, List.of())));
                })
                .list();
    }

    private List<LineItem> lineItems(UUID consolidationId) {
        return jdbc.sql("""
                        SELECT id, document_id, description, amount, state, page FROM core.claim_line_items
                        WHERE consolidation_id = ? ORDER BY document_id, position
                        """)
                .param(consolidationId)
                .query((row, index) -> new LineItem(row.getObject("id", UUID.class), row.getObject("document_id", UUID.class),
                        row.getString("description"), row.getBigDecimal("amount"), row.getString("state"), row.getInt("page")))
                .list();
    }

    private List<RiskInput.Finding> findings(UUID consolidationId) {
        return jdbc.sql("""
                        SELECT code, field_name, document_id, expected_value, actual_value FROM core.validation_findings
                        WHERE consolidation_id = ? ORDER BY id
                        """)
                .param(consolidationId)
                .query((row, index) -> new RiskInput.Finding(row.getString("code"), row.getString("field_name"),
                        row.getObject("document_id", UUID.class), row.getString("expected_value"), row.getString("actual_value")))
                .list();
    }

    private List<SharedFile> sharedFiles(UUID claimId) {
        return jdbc.sql("""
                        SELECT mine.id AS document_id, c.id AS other_claim, c.claim_number, other.id AS other_document
                        FROM core.documents mine
                        JOIN core.documents other ON other.sha256 = mine.sha256 AND other.owner_type = 'CLAIM'
                            AND other.owner_id <> mine.owner_id AND other.status = 'ACTIVE'
                        JOIN core.claims c ON c.id = other.owner_id
                        WHERE mine.owner_type = 'CLAIM' AND mine.owner_id = ? AND mine.status = 'ACTIVE' AND %s
                        ORDER BY mine.uploaded_at, c.claim_number
                        """.formatted(OPEN_CLAIM))
                .param(claimId)
                .query((row, index) -> new SharedFile(row.getObject("document_id", UUID.class),
                        row.getObject("other_claim", UUID.class), row.getString("claim_number"),
                        row.getObject("other_document", UUID.class)))
                .list();
    }

    /**
     * Bills in other claims' latest facts that share an invoice number, or at least two line item
     * amounts, with this claim's bills. The rules then decide what counts as a match.
     */
    private List<OtherBill> otherBills(UUID claimId, UUID consolidationId, List<LineItem> lineItems) {
        Set<String> amounts = lineItems.stream()
                .filter(item -> item.amount() != null)
                .map(item -> item.amount().stripTrailingZeros().toPlainString())
                .collect(Collectors.toSet());
        // Normalized as the duplicate rules normalize invoice numbers: letters and digits, upper case.
        Set<String> invoices = jdbc.sql("""
                        SELECT DISTINCT upper(regexp_replace(value, '[^[:alnum:]]', '', 'g')) FROM core.claim_facts
                        WHERE consolidation_id = ? AND field_name = 'bill_number' AND state = 'PRESENT'
                        """)
                .param(consolidationId)
                .query(String.class)
                .list()
                .stream()
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toSet());
        if (amounts.isEmpty() && invoices.isEmpty()) {
            return List.of();
        }
        // Array literals are safe here: amounts are plain decimals and invoice keys are letters and digits.
        String amountArray = "{" + String.join(",", amounts) + "}";
        String invoiceArray = "{" + String.join(",", invoices) + "}";
        List<Candidate> candidates = jdbc.sql("""
                        WITH latest AS (
                            SELECT DISTINCT ON (cc.claim_id) cc.id, cc.claim_id FROM core.claim_consolidations cc
                            JOIN core.claims c ON c.id = cc.claim_id
                            WHERE cc.claim_id <> ? AND %s
                            ORDER BY cc.claim_id, cc.sequence DESC
                        )
                        SELECT l.claim_id, l.id AS consolidation_id, li.document_id FROM latest l
                        JOIN core.claim_line_items li ON li.consolidation_id = l.id AND li.state = 'PRESENT'
                        WHERE li.amount = ANY (?::numeric[])
                        GROUP BY l.claim_id, l.id, li.document_id HAVING count(*) >= 2
                        UNION
                        SELECT l.claim_id, l.id, f.document_id FROM latest l
                        JOIN core.claim_facts f ON f.consolidation_id = l.id AND f.field_name = 'bill_number' AND f.state = 'PRESENT'
                        WHERE upper(regexp_replace(f.value, '[^[:alnum:]]', '', 'g')) = ANY (?::text[])
                        """.formatted(OPEN_CLAIM))
                .params(claimId, amountArray, invoiceArray)
                .query((row, index) -> new Candidate(row.getObject("claim_id", UUID.class),
                        row.getObject("consolidation_id", UUID.class), row.getObject("document_id", UUID.class)))
                .list();
        List<OtherBill> bills = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ClaimHeader header = jdbc.sql("""
                            SELECT c.claim_number, c.hospital_name FROM core.claims c
                            JOIN core.documents d ON d.owner_id = c.id AND d.owner_type = 'CLAIM'
                            WHERE c.id = ? AND d.id = ? AND d.status = 'ACTIVE'
                            """)
                    .params(candidate.claimId(), candidate.documentId())
                    .query((row, index) -> new ClaimHeader(row.getString("claim_number"), row.getString("hospital_name")))
                    .optional()
                    .orElse(null);
            if (header == null) {
                continue;
            }
            String number = jdbc.sql("""
                            SELECT value FROM core.claim_facts
                            WHERE consolidation_id = ? AND document_id = ? AND field_name = 'bill_number' AND state = 'PRESENT'
                            """)
                    .params(candidate.consolidationId(), candidate.documentId())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            List<ItemKey> items = jdbc.sql("""
                            SELECT description, amount FROM core.claim_line_items
                            WHERE consolidation_id = ? AND document_id = ? AND state = 'PRESENT' ORDER BY position
                            """)
                    .params(candidate.consolidationId(), candidate.documentId())
                    .query((row, index) -> new ItemKey(row.getString("description"), row.getBigDecimal("amount")))
                    .list();
            bills.add(new OtherBill(candidate.claimId(), header.number(), candidate.documentId(), header.hospital(), number, items));
        }
        return bills;
    }

    private record Candidate(UUID claimId, UUID consolidationId, UUID documentId) {}

    private record ClaimHeader(String number, String hospital) {}

    /** Same policy only: a family floater's members share it, and other policies' patients are other people. */
    private List<OtherClaim> otherClaims(ClaimRecord claim) {
        return jdbc.sql("""
                        SELECT c.id, c.claim_number, c.patient_name, c.hospital_name, c.admission_date, c.discharge_date,
                            c.claimed_amount
                        FROM core.claims c
                        WHERE c.policy_id = ? AND c.id <> ? AND %s
                            AND c.admission_date BETWEEN ? AND ?
                        ORDER BY c.admission_date, c.claim_number
                        """.formatted(OPEN_CLAIM))
                .params(claim.policyId(), claim.id(), claim.admissionDate().minusDays(HISTORY_DAYS),
                        claim.admissionDate().plusDays(HISTORY_DAYS))
                .query((row, index) -> new OtherClaim(row.getObject("id", UUID.class), row.getString("claim_number"),
                        row.getString("patient_name"), row.getString("hospital_name"),
                        row.getObject("admission_date", LocalDate.class), row.getObject("discharge_date", LocalDate.class),
                        row.getBigDecimal("claimed_amount")))
                .list();
    }
}
