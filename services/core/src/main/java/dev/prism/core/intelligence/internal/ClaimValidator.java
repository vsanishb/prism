package dev.prism.core.intelligence.internal;

import static dev.prism.core.intelligence.internal.ConsolidatedClaim.CONFLICTING;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.MISSING;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.PRESENT;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.UNCERTAIN;

import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.DocumentLineItem;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.ExtractedDocument;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Finding;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Deterministic checks on consolidated facts. Findings describe what to look at; none decides anything. */
final class ClaimValidator {

    static final String MISSING_REQUIRED_DOCUMENT = "MISSING_REQUIRED_DOCUMENT";

    private static final Map<String, List<String>> REQUIRED_DOCUMENTS = Map.of(
            "REIMBURSEMENT", List.of("CLAIM_FORM", "HOSPITAL_BILL", "DISCHARGE_SUMMARY"),
            "CASHLESS", List.of("HOSPITAL_BILL", "DISCHARGE_SUMMARY"));
    private static final Set<String> BILLS = Set.of("HOSPITAL_BILL", "ITEMIZED_INVOICE");

    private ClaimValidator() {}

    static List<Finding> validate(
            ClaimRecord claim,
            List<ExtractedDocument> documents,
            List<Fact> facts,
            List<DocumentLineItem> lineItems) {
        List<Finding> findings = new ArrayList<>();
        requiredDocuments(claim, documents, findings);
        documentTypes(documents, findings);
        factStates(facts, findings);
        stayDates(facts, findings);
        enteredDetails(claim, facts, findings);
        lineItemTotals(documents, facts, lineItems, findings);
        billedAmount(claim, documents, facts, findings);
        return findings;
    }

    private static void requiredDocuments(ClaimRecord claim, List<ExtractedDocument> documents, List<Finding> findings) {
        Set<String> present = documents.stream().map(ExtractedDocument::effectiveType).collect(Collectors.toSet());
        REQUIRED_DOCUMENTS.get(claim.type()).stream()
                .filter(required -> !present.contains(required))
                .forEach(required -> findings.add(new Finding(
                        MISSING_REQUIRED_DOCUMENT, Severity.ERROR, null, null, required, null)));
    }

    private static void documentTypes(List<ExtractedDocument> documents, List<Finding> findings) {
        for (ExtractedDocument document : documents) {
            if (!document.declaredType().equals(document.effectiveType())) {
                findings.add(new Finding(
                        "DOCUMENT_TYPE_DIFFERS",
                        Severity.INFO,
                        null,
                        document.documentId(),
                        document.declaredType(),
                        document.effectiveType()));
            }
        }
    }

    private static void factStates(List<Fact> facts, List<Finding> findings) {
        for (Fact fact : facts) {
            switch (fact.state()) {
                case CONFLICTING -> findings.add(new Finding(
                        "VALUES_CONFLICT", Severity.WARNING, fact.name(), null, null, null));
                case UNCERTAIN -> {
                    if (fact.critical()) {
                        findings.add(new Finding(
                                "VALUE_UNCERTAIN", Severity.WARNING, fact.name(), fact.documentId(), null, null));
                    }
                }
                case MISSING -> {
                    // A claim-level field may legitimately come from a document not yet supplied.
                    if (fact.critical() && fact.documentId() != null) {
                        findings.add(new Finding(
                                "VALUE_MISSING", Severity.WARNING, fact.name(), fact.documentId(), null, null));
                    }
                }
                default -> {}
            }
        }
    }

    private static void stayDates(List<Fact> facts, List<Finding> findings) {
        Optional<String> admission = stated(facts, "admission_date");
        Optional<String> discharge = stated(facts, "discharge_date");
        if (admission.isPresent()
                && discharge.isPresent()
                && LocalDate.parse(discharge.get()).isBefore(LocalDate.parse(admission.get()))) {
            findings.add(new Finding(
                    "DISCHARGE_BEFORE_ADMISSION", Severity.ERROR, "discharge_date", null, admission.get(), discharge.get()));
        }
    }

    /** Compares what staff entered with what the documents say. The documents are the authority. */
    private static void enteredDetails(ClaimRecord claim, List<Fact> facts, List<Finding> findings) {
        compare("patient_name", "TEXT", claim.patientName(), facts, findings);
        compare("hospital_name", "TEXT", claim.hospitalName(), facts, findings);
        compare("admission_date", "DATE", claim.admissionDate().toString(), facts, findings);
        compare("discharge_date", "DATE", claim.dischargeDate().toString(), facts, findings);
        compare("claimed_amount", "AMOUNT", claim.claimedAmount().toPlainString(), facts, findings);
    }

    private static void compare(String field, String kind, String entered, List<Fact> facts, List<Finding> findings) {
        stated(facts, field)
                .filter(value -> !FactConsolidator.sameValue(kind, entered, value))
                .ifPresent(value -> findings.add(new Finding(
                        "DIFFERS_FROM_ENTERED_DETAILS", Severity.WARNING, field, null, entered, value)));
    }

    private static void lineItemTotals(
            List<ExtractedDocument> documents,
            List<Fact> facts,
            List<DocumentLineItem> lineItems,
            List<Finding> findings) {
        for (ExtractedDocument document : documents) {
            UUID id = document.documentId();
            List<DocumentLineItem> items = lineItems.stream().filter(item -> item.documentId().equals(id)).toList();
            Optional<BigDecimal> total = documentTotal(facts, id);
            if (items.isEmpty() || total.isEmpty()) {
                continue;
            }
            if (items.stream().anyMatch(item -> !PRESENT.equals(item.item().state()))) {
                findings.add(new Finding("LINE_ITEMS_UNVERIFIED", Severity.WARNING, "total_amount", id, null, null));
                continue;
            }
            BigDecimal sum = items.stream()
                    .map(item -> item.item().amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(total.get()) != 0) {
                findings.add(new Finding(
                        "LINE_ITEMS_DO_NOT_MATCH_TOTAL",
                        Severity.WARNING,
                        "total_amount",
                        id,
                        total.get().toPlainString(),
                        sum.toPlainString()));
            }
        }
    }

    /** Skipped unless every bill states a total: a partial sum would give a false difference. */
    private static void billedAmount(
            ClaimRecord claim, List<ExtractedDocument> documents, List<Fact> facts, List<Finding> findings) {
        List<UUID> bills = documents.stream()
                .filter(document -> BILLS.contains(document.effectiveType()))
                .map(ExtractedDocument::documentId)
                .toList();
        List<Optional<BigDecimal>> totals = bills.stream().map(id -> documentTotal(facts, id)).toList();
        if (bills.isEmpty() || totals.stream().anyMatch(Optional::isEmpty)) {
            return;
        }
        BigDecimal billed = totals.stream().map(Optional::orElseThrow).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (billed.compareTo(claim.claimedAmount()) != 0) {
            findings.add(new Finding(
                    "CLAIMED_AMOUNT_DIFFERS_FROM_BILLS",
                    Severity.WARNING,
                    "claimed_amount",
                    null,
                    claim.claimedAmount().toPlainString(),
                    billed.toPlainString()));
        }
    }

    private static Optional<BigDecimal> documentTotal(List<Fact> facts, UUID documentId) {
        return facts.stream()
                .filter(fact -> "total_amount".equals(fact.name())
                        && documentId.equals(fact.documentId())
                        && PRESENT.equals(fact.state()))
                .map(Fact::amount)
                .findFirst();
    }

    private static Optional<String> stated(List<Fact> facts, String name) {
        return facts.stream()
                .filter(fact -> name.equals(fact.name()) && fact.documentId() == null && PRESENT.equals(fact.state()))
                .map(Fact::value)
                .findFirst();
    }
}
