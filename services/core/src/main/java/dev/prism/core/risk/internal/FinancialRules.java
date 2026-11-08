package dev.prism.core.risk.internal;

import static dev.prism.core.risk.internal.Facts.BILLS;

import dev.prism.core.risk.Category;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.IndicatorKind;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RiskInput.LineItem;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.Severity;
import dev.prism.core.risk.internal.Rule.Finding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amounts that do not add up, or that sit at the edge of the cover. Thresholds are judgment, versioned
 * with each rule: with no history of real claims, there is no baseline to set them from (plan).
 */
final class FinancialRules {

    /** Rounding on a bill should never come near this. */
    private static final BigDecimal ABOVE_BILLS_TOLERANCE = new BigDecimal("0.01");

    private static final BigDecimal NEAR_SUM_INSURED = new BigDecimal("0.90");
    /** Per day of stay, in INR, including any procedure: above what an Indian private hospital day costs. */
    private static final BigDecimal HIGH_DAILY_AMOUNT_INR = new BigDecimal("250000");

    private FinancialRules() {}

    static List<Rule> all() {
        return List.of(itemsDoNotMatchTotal(), repeatedItems(), aboveBills(), aboveSumInsured(), nearSumInsured(), highDailyAmount());
    }

    private static Rule itemsDoNotMatchTotal() {
        return new Rule(
                new RuleDescription("FIN-01", 1, Category.FINANCIAL, IndicatorKind.DATA_INCONSISTENCY, Severity.HIGH,
                        "Line items do not add up to the bill's total",
                        "Whether each bill's line items add up to the total it states"),
                input -> {
                    Facts facts = new Facts(input);
                    String currency = input.claim().currency();
                    return Facts.findings(input, "LINE_ITEMS_DO_NOT_MATCH_TOTAL", null).stream()
                            .filter(finding -> finding.documentId() != null)
                            .map(finding -> {
                                String type = facts.typeOf(finding.documentId());
                                BigDecimal total = new BigDecimal(finding.expected());
                                BigDecimal sum = new BigDecimal(finding.actual());
                                List<Evidence> evidence = new ArrayList<>(facts.documentFactEvidence(
                                        new RiskInput.Document(finding.documentId(), type), "total_amount",
                                        "Stated total " + Wording.money(total, currency)));
                                evidence.add(Evidence.onPage(Wording.document(type), "Line items add up to " + Wording.money(sum, currency),
                                        finding.documentId(), null));
                                return new Finding(
                                        "%s states a total of %s, but its line items add up to %s.".formatted(
                                                Wording.document(type), Wording.money(total, currency), Wording.money(sum, currency)),
                                        evidence);
                            })
                            .toList();
                });
    }

    /** Within one bill only: a summary bill and its itemized invoice legitimately share items. */
    private static Rule repeatedItems() {
        return new Rule(
                new RuleDescription("FIN-02", 1, Category.FINANCIAL, IndicatorKind.RISK_INDICATOR, Severity.MEDIUM,
                        "A line item is repeated on a bill",
                        "Whether any bill lists the same item, at the same amount, more than once"),
                input -> {
                    Facts facts = new Facts(input);
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
                        Map<String, List<LineItem>> byKey = new LinkedHashMap<>();
                        for (LineItem item : facts.presentItems(bill.id())) {
                            byKey.computeIfAbsent(Normalized.description(item.description()) + "|" + item.amount().stripTrailingZeros(),
                                    key -> new ArrayList<>()).add(item);
                        }
                        byKey.values().stream().filter(items -> items.size() > 1).forEach(items -> found.add(new Finding(
                                "%s lists the same item at %s %d times.".formatted(
                                        Wording.document(bill.type()), Wording.money(items.getFirst().amount(), input.claim().currency()),
                                        items.size()),
                                items.stream()
                                        .map(item -> Evidence.ofLineItem(Wording.page(bill.type(), item.page()),
                                                item.description(), bill.id(), item.page(), item.id()))
                                        .toList())));
                    }
                    return found;
                });
    }

    private static Rule aboveBills() {
        return new Rule(
                new RuleDescription("FIN-03", 1, Category.FINANCIAL, IndicatorKind.RISK_INDICATOR, Severity.HIGH,
                        "The claimed amount is more than the bills",
                        "Whether the amount claimed is more than the bills' totals"),
                input -> {
                    Facts facts = new Facts(input);
                    BigDecimal claimed = input.claim().claimedAmount();
                    String currency = input.claim().currency();
                    return facts.billsTotal()
                            .filter(total -> total.signum() > 0
                                    && claimed.compareTo(total.multiply(BigDecimal.ONE.add(ABOVE_BILLS_TOLERANCE))) > 0)
                            .map(total -> List.of(new Finding(
                                    "The claim asks for %s, but the bills total %s.".formatted(
                                            Wording.money(claimed, currency), Wording.money(total, currency)),
                                    billTotals(facts, input))))
                            .orElse(List.of());
                });
    }

    private static List<Evidence> billTotals(Facts facts, RiskInput input) {
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("Entered for the claim", "Claimed " + Wording.money(input.claim().claimedAmount(),
                input.claim().currency()), null, null, null, null, null));
        for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
            facts.documentAmount(bill.id(), "total_amount").ifPresent(total -> evidence.addAll(facts.documentFactEvidence(
                    bill, "total_amount", "Total " + Wording.money(total, input.claim().currency()))));
        }
        return evidence;
    }

    private static Rule aboveSumInsured() {
        return new Rule(
                new RuleDescription("FIN-04", 1, Category.FINANCIAL, IndicatorKind.RISK_INDICATOR, Severity.HIGH,
                        "The claimed amount is above the sum insured",
                        "Whether the amount claimed is more than the policy's sum insured"),
                input -> sumInsured(input)
                        .filter(sumInsured -> input.claim().claimedAmount().compareTo(sumInsured) > 0)
                        .map(sumInsured -> List.of(new Finding(
                                "The claim asks for %s, above the sum insured of %s.".formatted(
                                        Wording.money(input.claim().claimedAmount(), input.claim().currency()),
                                        Wording.money(sumInsured, input.claim().currency())),
                                List.of(policyEvidence(input, sumInsured)))))
                        .orElse(List.of()));
    }

    private static Rule nearSumInsured() {
        return new Rule(
                new RuleDescription("FIN-05", 1, Category.FINANCIAL, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "The claimed amount is close to the sum insured",
                        "Whether the amount claimed is between %s and 100%% of the sum insured".formatted(Wording.percent(NEAR_SUM_INSURED))),
                input -> sumInsured(input)
                        .filter(sumInsured -> {
                            BigDecimal claimed = input.claim().claimedAmount();
                            return claimed.compareTo(sumInsured) <= 0 && claimed.compareTo(sumInsured.multiply(NEAR_SUM_INSURED)) >= 0;
                        })
                        .map(sumInsured -> List.of(new Finding(
                                "The claim asks for %s of the sum insured.".formatted(Wording.percent(
                                        input.claim().claimedAmount().divide(sumInsured, MathContext.DECIMAL64))),
                                List.of(policyEvidence(input, sumInsured)))))
                        .orElse(List.of()));
    }

    private static Rule highDailyAmount() {
        return new Rule(
                new RuleDescription("FIN-06", 1, Category.FINANCIAL, IndicatorKind.RISK_INDICATOR, Severity.MEDIUM,
                        "A very high amount per day of stay",
                        "Whether the amount claimed per day of stay is above %s".formatted(Wording.money(HIGH_DAILY_AMOUNT_INR, "INR"))),
                input -> {
                    if (!"INR".equals(input.claim().currency())) {
                        return List.of();
                    }
                    long nights = Math.max(1, ChronoUnit.DAYS.between(input.claim().admissionDate(), input.claim().dischargeDate()));
                    BigDecimal daily = input.claim().claimedAmount().divide(BigDecimal.valueOf(nights), 2, java.math.RoundingMode.HALF_UP);
                    if (daily.compareTo(HIGH_DAILY_AMOUNT_INR) <= 0) {
                        return List.of();
                    }
                    return List.of(new Finding(
                            "The claim asks for %s a day, over %d %s of stay.".formatted(
                                    Wording.money(daily, "INR"), nights, nights == 1 ? "day" : "days"),
                            List.of(new Evidence("Entered for the claim", "%s to %s".formatted(
                                    Wording.date(input.claim().admissionDate()), Wording.date(input.claim().dischargeDate())),
                                    null, null, null, null, null))));
                });
    }

    private static java.util.Optional<BigDecimal> sumInsured(RiskInput input) {
        return java.util.Optional.ofNullable(input.policy().sumInsured())
                .filter(amount -> amount.signum() > 0 && input.policy().currency().equals(input.claim().currency()));
    }

    private static Evidence policyEvidence(RiskInput input, BigDecimal sumInsured) {
        return new Evidence("Policy version", "Sum insured " + Wording.money(sumInsured, input.policy().currency()),
                null, null, null, null, null);
    }
}
