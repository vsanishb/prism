package dev.prism.core.risk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything screening reads, loaded by core from its own records. Rules and features are pure
 * functions of this, so the same input always gives the same result.
 *
 * @param facts the latest consolidation's facts: claim-level ones have no document
 * @param findings the consolidation's validation findings
 * @param history other claims, found in the database. AI never searches other claims (D-013)
 */
public record RiskInput(
        ClaimDetails claim,
        PolicyDetails policy,
        List<Document> documents,
        List<Fact> facts,
        List<LineItem> lineItems,
        List<Finding> findings,
        History history) {

    /** The details entered at intake. */
    public record ClaimDetails(
            UUID id,
            String type,
            String patientName,
            String hospitalName,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            BigDecimal claimedAmount,
            String currency) {}

    /** @param coverStart when continuous cover began, or null if the register cannot say */
    public record PolicyDetails(BigDecimal sumInsured, String currency, LocalDate coverStart) {}

    /** @param type what the document is treated as: its confident reading, or the declared type */
    public record Document(UUID id, String type) {}

    public record Fact(
            UUID id, String name, String state, String value, BigDecimal amount, UUID documentId, List<Source> sources) {}

    /** Where one document stated a fact. */
    public record Source(UUID documentId, Integer page, String value) {}

    public record LineItem(UUID id, UUID documentId, String description, BigDecimal amount, String state, int page) {}

    public record Finding(String code, String field, UUID documentId, String expected, String actual) {}

    /**
     * @param sharedFiles this claim's documents whose exact file is also in another claim
     * @param otherBills bills in other claims that share an invoice number or line items with this claim's bills
     * @param otherClaims other submitted claims on the same policy, around this claim's dates
     */
    public record History(List<SharedFile> sharedFiles, List<OtherBill> otherBills, List<OtherClaim> otherClaims) {}

    public record SharedFile(UUID documentId, UUID otherClaimId, String otherClaimNumber, UUID otherDocumentId) {}

    public record OtherBill(
            UUID otherClaimId,
            String otherClaimNumber,
            UUID otherDocumentId,
            String hospitalName,
            String billNumber,
            List<ItemKey> items) {}

    public record ItemKey(String description, BigDecimal amount) {}

    public record OtherClaim(
            UUID id,
            String number,
            String patientName,
            String hospitalName,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            BigDecimal claimedAmount) {}
}
