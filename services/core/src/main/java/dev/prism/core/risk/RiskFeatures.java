package dev.prism.core.risk;

import dev.prism.core.risk.RiskInput.LineItem;
import dev.prism.core.risk.internal.Normalized;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The numbers the anomaly detectors see, computed here from facts core holds (D-082). Names and
 * order match the AI service's {@code risk-features-1}. A feature the facts cannot give is null, and
 * the detectors use the baseline median in its place, saying so.
 */
public final class RiskFeatures {

    public static final String VERSION = "risk-features-1";

    private static final Set<String> BILLS = Set.of("HOSPITAL_BILL", "ITEMIZED_INVOICE");
    private static final int PRIOR_CLAIM_DAYS = 365;

    private RiskFeatures() {}

    public static Map<String, Double> compute(RiskInput input) {
        RiskInput.ClaimDetails claim = input.claim();
        BigDecimal claimed = claim.claimedAmount();
        long nights = Math.max(0, ChronoUnit.DAYS.between(claim.admissionDate(), claim.dischargeDate()));
        List<LineItem> items = billItems(input);
        BigDecimal itemsSum = items.stream().map(LineItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal largest = items.stream().map(LineItem::amount).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        Map<String, Double> features = new LinkedHashMap<>();
        features.put("claimedAmount", claimed.doubleValue());
        features.put("sumInsuredRatio", sumInsuredRatio(input));
        features.put("lengthOfStayDays", (double) nights);
        features.put("amountPerDay", claimed.divide(BigDecimal.valueOf(Math.max(1, nights)), MathContext.DECIMAL64).doubleValue());
        features.put("lineItemCount", items.isEmpty() ? null : (double) items.size());
        features.put("largestItemShare", itemsSum.signum() > 0 ? largest.divide(itemsSum, MathContext.DECIMAL64).doubleValue() : null);
        features.put("billDeviation", billsTotal(input)
                .map(total -> claimed.divide(total, MathContext.DECIMAL64).subtract(BigDecimal.ONE).doubleValue())
                .orElse(null));
        features.put("priorClaims12m", (double) priorClaims(input));
        features.put("daysSinceCoverStart", input.policy().coverStart() == null
                ? null
                : (double) Math.max(0, ChronoUnit.DAYS.between(input.policy().coverStart(), claim.admissionDate())));
        return features;
    }

    private static Double sumInsuredRatio(RiskInput input) {
        BigDecimal sumInsured = input.policy().sumInsured();
        if (sumInsured == null || sumInsured.signum() <= 0 || !input.policy().currency().equals(input.claim().currency())) {
            return null;
        }
        return input.claim().claimedAmount().divide(sumInsured, MathContext.DECIMAL64).doubleValue();
    }

    private static List<LineItem> billItems(RiskInput input) {
        Set<java.util.UUID> bills = input.documents().stream()
                .filter(document -> BILLS.contains(document.type()))
                .map(RiskInput.Document::id)
                .collect(java.util.stream.Collectors.toSet());
        return input.lineItems().stream()
                .filter(item -> bills.contains(item.documentId()) && "PRESENT".equals(item.state()) && item.amount() != null)
                .toList();
    }

    private static java.util.Optional<BigDecimal> billsTotal(RiskInput input) {
        List<RiskInput.Document> bills = input.documents().stream().filter(document -> BILLS.contains(document.type())).toList();
        if (bills.isEmpty()) {
            return java.util.Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (RiskInput.Document bill : bills) {
            BigDecimal stated = input.facts().stream()
                    .filter(fact -> bill.id().equals(fact.documentId()) && "total_amount".equals(fact.name())
                            && "PRESENT".equals(fact.state()))
                    .map(RiskInput.Fact::amount)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (stated == null) {
                return java.util.Optional.empty();
            }
            total = total.add(stated);
        }
        return total.signum() > 0 ? java.util.Optional.of(total) : java.util.Optional.empty();
    }

    /** Earlier claims for the same patient on this policy, admitted in the year before this admission. */
    static int priorClaims(RiskInput input) {
        String patient = Normalized.name(input.claim().patientName());
        var admission = input.claim().admissionDate();
        return (int) input.history().otherClaims().stream()
                .filter(other -> !patient.isEmpty() && patient.equals(Normalized.name(other.patientName())))
                .filter(other -> other.admissionDate().isBefore(admission)
                        && !other.admissionDate().isBefore(admission.minusDays(PRIOR_CLAIM_DAYS)))
                .count();
    }
}
