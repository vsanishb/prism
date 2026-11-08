package dev.prism.core.risk.internal;

import static dev.prism.core.risk.internal.Facts.BILLS;

import dev.prism.core.risk.Category;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.IndicatorKind;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RiskInput.ItemKey;
import dev.prism.core.risk.RiskInput.LineItem;
import dev.prism.core.risk.RiskInput.OtherBill;
import dev.prism.core.risk.RiskInput.OtherClaim;
import dev.prism.core.risk.RiskInput.SharedFile;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.Severity;
import dev.prism.core.risk.internal.Rule.Finding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Matches with other claims, found by core in its own database. A match names the other claim and
 * what matched, never that claim's content. These are fraud indicators: reasons to investigate,
 * which legitimate events such as a readmission or a resubmitted claim can also explain.
 */
final class DuplicateRules {

    /** Near-identical bills (D-087): at least this many items read, and this share matching. */
    static final int NEAR_MATCH_MIN_ITEMS = 4;

    static final BigDecimal NEAR_MATCH_SHARE = new BigDecimal("0.70");
    private static final int SIMILAR_CLAIM_DAYS = 30;
    private static final BigDecimal SIMILAR_AMOUNT = new BigDecimal("0.15");

    private DuplicateRules() {}

    static List<Rule> all() {
        return List.of(sameFile(), invoiceReused(), nearIdenticalBill(), overlappingStay(), similarClaim());
    }

    private static Rule sameFile() {
        return new Rule(
                new RuleDescription("DUP-01", 1, Category.DUPLICATE, IndicatorKind.FRAUD_INDICATOR, Severity.HIGH,
                        "The same file is in another claim",
                        "Whether any document is byte-for-byte the same file as a document in another claim"),
                input -> {
                    Facts facts = new Facts(input);
                    return input.history().sharedFiles().stream()
                            .map(shared -> new Finding(
                                    "%s is the same file as a document in another claim.".formatted(Wording.document(facts.typeOf(shared.documentId()))),
                                    List.of(
                                            Evidence.onPage(Wording.document(facts.typeOf(shared.documentId())), "Identical file (SHA-256)",
                                                    shared.documentId(), null),
                                            Evidence.otherClaim("Other claim", shared.otherClaimNumber(), shared.otherClaimId()))))
                            .toList();
                });
    }

    private static Rule invoiceReused() {
        return new Rule(
                new RuleDescription("DUP-02", 1, Category.DUPLICATE, IndicatorKind.FRAUD_INDICATOR, Severity.HIGH,
                        "An invoice number is reused at the same hospital",
                        "Whether a bill's number already appears on a bill from the same hospital in another claim"),
                input -> {
                    Facts facts = new Facts(input);
                    String hospital = Normalized.name(input.claim().hospitalName());
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
                        String number = Normalized.invoice(facts.documentValue(bill.id(), "bill_number").orElse(""));
                        if (number.isEmpty()) {
                            continue;
                        }
                        input.history().otherBills().stream()
                                .filter(other -> number.equals(Normalized.invoice(other.billNumber()))
                                        && hospital.equals(Normalized.name(other.hospitalName()))
                                        && !sameFile(input, bill.id(), other.otherDocumentId()))
                                .map(OtherBill::otherClaimId)
                                .distinct()
                                .forEach(otherClaim -> {
                                    OtherBill other = first(input, otherClaim);
                                    List<Evidence> evidence = new ArrayList<>(facts.documentFactEvidence(bill, "bill_number",
                                            "Bill number " + facts.documentValue(bill.id(), "bill_number").orElseThrow()));
                                    evidence.add(Evidence.otherClaim("Other claim", other.otherClaimNumber(), other.otherClaimId()));
                                    found.add(new Finding(
                                            "%s has the same invoice number as a bill from the same hospital in another claim."
                                                    .formatted(Wording.document(bill.type())),
                                            evidence));
                                });
                    }
                    return found;
                });
    }

    private static Rule nearIdenticalBill() {
        return new Rule(
                new RuleDescription("DUP-03", 1, Category.DUPLICATE, IndicatorKind.FRAUD_INDICATOR, Severity.HIGH,
                        "A bill closely matches a bill in another claim",
                        "Whether %s or more of a bill's line items match, by description and amount, a bill from the same hospital in another claim"
                                .formatted(Wording.percent(NEAR_MATCH_SHARE))),
                input -> {
                    Facts facts = new Facts(input);
                    String hospital = Normalized.name(input.claim().hospitalName());
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
                        List<LineItem> items = facts.presentItems(bill.id());
                        if (items.size() < NEAR_MATCH_MIN_ITEMS) {
                            continue;
                        }
                        for (OtherBill other : input.history().otherBills()) {
                            if (other.items().size() < NEAR_MATCH_MIN_ITEMS
                                    || !hospital.equals(Normalized.name(other.hospitalName()))
                                    || sameFile(input, bill.id(), other.otherDocumentId())) {
                                continue;
                            }
                            List<LineItem> matched = matching(items, other.items());
                            int larger = Math.max(items.size(), other.items().size());
                            if (BigDecimal.valueOf(matched.size()).compareTo(NEAR_MATCH_SHARE.multiply(BigDecimal.valueOf(larger))) < 0) {
                                continue;
                            }
                            List<Evidence> evidence = new ArrayList<>();
                            for (LineItem item : matched) {
                                evidence.add(Evidence.ofLineItem(Wording.page(bill.type(), item.page()), item.description(),
                                        bill.id(), item.page(), item.id()));
                            }
                            evidence.add(Evidence.otherClaim("Other claim", other.otherClaimNumber(), other.otherClaimId()));
                            found.add(new Finding(
                                    "%d of %d line items on the %s match, by description and amount, a bill from the same hospital in another claim."
                                            .formatted(matched.size(), items.size(), Wording.document(bill.type()).toLowerCase(java.util.Locale.ROOT)),
                                    evidence));
                        }
                    }
                    return found;
                });
    }

    /** Matches each of this bill's items at most once against the other bill's items. */
    static List<LineItem> matching(List<LineItem> items, List<ItemKey> others) {
        Map<String, Integer> available = new HashMap<>();
        for (ItemKey other : others) {
            available.merge(key(other.description(), other.amount()), 1, Integer::sum);
        }
        List<LineItem> matched = new ArrayList<>();
        for (LineItem item : items) {
            String key = key(item.description(), item.amount());
            int left = available.getOrDefault(key, 0);
            if (left > 0) {
                available.put(key, left - 1);
                matched.add(item);
            }
        }
        return matched;
    }

    private static String key(String description, BigDecimal amount) {
        return Normalized.description(description) + "|" + amount.stripTrailingZeros().toPlainString();
    }

    private static Rule overlappingStay() {
        return new Rule(
                new RuleDescription("DUP-04", 1, Category.DUPLICATE, IndicatorKind.FRAUD_INDICATOR, Severity.HIGH,
                        "The same patient has another claim for an overlapping stay",
                        "Whether another claim on this policy is for the same patient, with a stay that overlaps this one"),
                input -> samePatient(input).stream()
                        .filter(other -> !other.admissionDate().isAfter(input.claim().dischargeDate())
                                && !input.claim().admissionDate().isAfter(other.dischargeDate()))
                        .map(other -> new Finding(
                                "Another claim for the same patient covers a stay from %s to %s, overlapping this one."
                                        .formatted(Wording.date(other.admissionDate()), Wording.date(other.dischargeDate())),
                                List.of(Evidence.otherClaim("Other claim", other.number(), other.id()))))
                        .toList());
    }

    private static Rule similarClaim() {
        return new Rule(
                new RuleDescription("DUP-05", 1, Category.DUPLICATE, IndicatorKind.FRAUD_INDICATOR, Severity.MEDIUM,
                        "A similar claim shortly before or after",
                        "Whether another claim on this policy is for the same patient and hospital, within %d days, for an amount within %s"
                                .formatted(SIMILAR_CLAIM_DAYS, Wording.percent(SIMILAR_AMOUNT))),
                input -> {
                    String hospital = Normalized.name(input.claim().hospitalName());
                    BigDecimal claimed = input.claim().claimedAmount();
                    return samePatient(input).stream()
                            .filter(other -> hospital.equals(Normalized.name(other.hospitalName())))
                            .filter(other -> {
                                long gap = gapDays(input.claim().admissionDate(), input.claim().dischargeDate(), other);
                                return gap > 0 && gap <= SIMILAR_CLAIM_DAYS;
                            })
                            .filter(other -> claimed.subtract(other.claimedAmount()).abs()
                                    .compareTo(claimed.max(other.claimedAmount()).multiply(SIMILAR_AMOUNT)) <= 0)
                            .map(other -> new Finding(
                                    "Another claim for the same patient at the same hospital, %d days apart, is for a similar amount."
                                            .formatted(gapDays(input.claim().admissionDate(), input.claim().dischargeDate(), other)),
                                    List.of(Evidence.otherClaim("Other claim", other.number(), other.id()))))
                            .toList();
                });
    }

    /** Days between the two stays: zero or less when they overlap, which DUP-04 covers. */
    private static long gapDays(LocalDate admission, LocalDate discharge, OtherClaim other) {
        if (other.dischargeDate().isBefore(admission)) {
            return ChronoUnit.DAYS.between(other.dischargeDate(), admission);
        }
        if (discharge.isBefore(other.admissionDate())) {
            return ChronoUnit.DAYS.between(discharge, other.admissionDate());
        }
        return 0;
    }

    static List<OtherClaim> samePatient(RiskInput input) {
        String patient = Normalized.name(input.claim().patientName());
        return input.history().otherClaims().stream()
                .filter(other -> !patient.isEmpty() && patient.equals(Normalized.name(other.patientName())))
                .toList();
    }

    private static boolean sameFile(RiskInput input, UUID documentId, UUID otherDocumentId) {
        Set<UUID> copies = input.history().sharedFiles().stream()
                .filter(shared -> shared.documentId().equals(documentId))
                .map(SharedFile::otherDocumentId)
                .collect(Collectors.toSet());
        return copies.contains(otherDocumentId);
    }

    private static OtherBill first(RiskInput input, UUID otherClaimId) {
        return input.history().otherBills().stream().filter(bill -> bill.otherClaimId().equals(otherClaimId)).findFirst().orElseThrow();
    }
}
