package dev.prism.core.intelligence.internal;

import dev.prism.core.policyknowledge.PublishedKnowledge.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Arithmetic for the Policy Agent's findings (D-029). The model names which confirmed value applies,
 * and in what role. This computes with it, showing inputs and steps. A result is a computation for a
 * reviewer to check, never a payable amount, and an unknown input gives no result rather than a guess.
 */
final class AssessmentCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private AssessmentCalculator() {}

    /** The claim's established facts. Empty when a fact is missing, uncertain, or conflicting. */
    record Inputs(
            Optional<LocalDate> admission,
            Optional<LocalDate> discharge,
            Optional<BigDecimal> claimed,
            BigDecimal sumInsured,
            String currency,
            LocalDate coverStart) {}

    record Calculation(
            String role,
            Map<String, String> inputs,
            List<String> steps,
            BigDecimal resultAmount,
            LocalDate resultDate,
            String outcome) {}

    static Calculation calculate(String role, Value value, Inputs facts) {
        Map<String, String> inputs = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        BigDecimal figure;
        try {
            figure = new BigDecimal(value.value());
        } catch (NumberFormatException exception) {
            inputs.put("Policy value", value.name() + ": " + value.value());
            return new Calculation(role, inputs, steps, null, null, "The confirmed value is not a number, so nothing was calculated.");
        }
        inputs.put("Policy value", describe(value, figure, facts.currency()));
        return switch (role) {
            case "WAITING_PERIOD" -> waitingPeriod(figure, value.unit(), facts, inputs, steps);
            case "AMOUNT_LIMIT" -> amountLimit(figure, facts, inputs, steps);
            case "DAILY_LIMIT" -> dailyLimit(figure, value.kind(), facts, inputs, steps);
            case "PERCENT_OF_SUM_INSURED" -> percentOfSumInsured(figure, facts, inputs, steps);
            case "COPAYMENT" -> copayment(figure, facts, inputs, steps);
            case "DEDUCTIBLE" -> deductible(figure, facts, inputs, steps);
            default -> throw new IllegalArgumentException("Unknown role " + role);
        };
    }

    private static Calculation waitingPeriod(
            BigDecimal figure, String unit, Inputs facts, Map<String, String> inputs, List<String> steps) {
        inputs.put("Cover began", day(facts.coverStart()));
        if (figure.stripTrailingZeros().scale() > 0) {
            return new Calculation("WAITING_PERIOD", inputs, steps, null, null, "The waiting period is not a whole number.");
        }
        long count = figure.longValueExact();
        LocalDate ends;
        switch (unit == null ? "" : unit) {
            case "DAYS" -> ends = facts.coverStart().plusDays(count);
            case "MONTHS" -> ends = facts.coverStart().plusMonths(count);
            case "YEARS" -> ends = facts.coverStart().plusYears(count);
            default -> {
                return new Calculation(
                        "WAITING_PERIOD", inputs, steps, null, null,
                        "Only periods in days, months, or years can be dated, so this one was not.");
            }
        }
        steps.add("%s + %d %s = %s".formatted(day(facts.coverStart()), count, unit.toLowerCase(Locale.ENGLISH), day(ends)));
        steps.add("Continuous cover is taken from the policy register's consecutive versions.");
        if (facts.admission().isEmpty()) {
            return new Calculation(
                    "WAITING_PERIOD", inputs, steps, null, ends,
                    "The waiting period ends on %s. The admission date is not established, so it cannot be compared."
                            .formatted(day(ends)));
        }
        LocalDate admission = facts.admission().get();
        inputs.put("Admission", day(admission));
        boolean within = admission.isBefore(ends);
        steps.add("Admission %s is %s %s".formatted(day(admission), within ? "before" : "on or after", day(ends)));
        return new Calculation(
                "WAITING_PERIOD", inputs, steps, null, ends,
                within
                        ? "Admission on %s falls within the waiting period, which ends on %s.".formatted(day(admission), day(ends))
                        : "Admission on %s is after the waiting period, which ended on %s.".formatted(day(admission), day(ends)));
    }

    private static Calculation amountLimit(BigDecimal limit, Inputs facts, Map<String, String> inputs, List<String> steps) {
        inputs.put("Limit", money(limit, facts.currency()));
        if (facts.claimed().isEmpty()) {
            return new Calculation(
                    "AMOUNT_LIMIT", inputs, steps, limit, null,
                    "The limit is %s. The claimed amount is not established, so it cannot be compared."
                            .formatted(money(limit, facts.currency())));
        }
        BigDecimal claimed = facts.claimed().get();
        inputs.put("Claimed", money(claimed, facts.currency()));
        BigDecimal within = claimed.min(limit);
        steps.add("Lower of claimed %s and limit %s = %s".formatted(
                money(claimed, facts.currency()), money(limit, facts.currency()), money(within, facts.currency())));
        String outcome = claimed.compareTo(limit) > 0
                ? "The claimed amount is above this limit by %s.".formatted(money(claimed.subtract(limit), facts.currency()))
                : "The claimed amount is within this limit.";
        return new Calculation("AMOUNT_LIMIT", inputs, steps, within, null, outcome);
    }

    private static Calculation dailyLimit(
            BigDecimal figure, String kind, Inputs facts, Map<String, String> inputs, List<String> steps) {
        BigDecimal perDay;
        if ("PERCENTAGE".equals(kind)) {
            inputs.put("Sum insured", money(facts.sumInsured(), facts.currency()));
            perDay = scale(facts.sumInsured().multiply(figure).divide(HUNDRED));
            steps.add("%s%% of %s = %s per day".formatted(
                    figure.stripTrailingZeros().toPlainString(), money(facts.sumInsured(), facts.currency()),
                    money(perDay, facts.currency())));
        } else {
            perDay = figure;
        }
        inputs.put("Per day", money(perDay, facts.currency()));
        if (facts.admission().isEmpty() || facts.discharge().isEmpty()) {
            return new Calculation(
                    "DAILY_LIMIT", inputs, steps, null, null,
                    "The limit is %s per day. The stay's dates are not established, so no total was calculated."
                            .formatted(money(perDay, facts.currency())));
        }
        // A same-day discharge still counts as one day of stay.
        long days = Math.max(1, ChronoUnit.DAYS.between(facts.admission().get(), facts.discharge().get()));
        inputs.put("Days of stay", Long.toString(days));
        BigDecimal total = scale(perDay.multiply(BigDecimal.valueOf(days)));
        steps.add("%s × %d days = %s".formatted(money(perDay, facts.currency()), days, money(total, facts.currency())));
        return new Calculation(
                "DAILY_LIMIT", inputs, steps, total, null,
                "Up to %s for a stay of %d days, under this limit. Which bill lines it covers is for the reviewer."
                        .formatted(money(total, facts.currency()), days));
    }

    private static Calculation percentOfSumInsured(BigDecimal percent, Inputs facts, Map<String, String> inputs, List<String> steps) {
        inputs.put("Sum insured", money(facts.sumInsured(), facts.currency()));
        BigDecimal cap = scale(facts.sumInsured().multiply(percent).divide(HUNDRED));
        steps.add("%s%% of %s = %s".formatted(
                percent.stripTrailingZeros().toPlainString(), money(facts.sumInsured(), facts.currency()), money(cap, facts.currency())));
        return new Calculation("PERCENT_OF_SUM_INSURED", inputs, steps, cap, null, "This limit is %s.".formatted(money(cap, facts.currency())));
    }

    private static Calculation copayment(BigDecimal percent, Inputs facts, Map<String, String> inputs, List<String> steps) {
        if (facts.claimed().isEmpty()) {
            return new Calculation(
                    "COPAYMENT", inputs, steps, null, null,
                    "The claimed amount is not established, so the co-payment was not calculated.");
        }
        BigDecimal claimed = facts.claimed().get();
        inputs.put("Claimed", money(claimed, facts.currency()));
        BigDecimal share = scale(claimed.multiply(percent).divide(HUNDRED));
        steps.add("%s%% of %s = %s".formatted(
                percent.stripTrailingZeros().toPlainString(), money(claimed, facts.currency()), money(share, facts.currency())));
        return new Calculation(
                "COPAYMENT", inputs, steps, share, null,
                "If this co-payment applies, the insured's share of the claimed amount is %s. Whether its conditions are met is for the reviewer."
                        .formatted(money(share, facts.currency())));
    }

    private static Calculation deductible(BigDecimal amount, Inputs facts, Map<String, String> inputs, List<String> steps) {
        inputs.put("Deductible", money(amount, facts.currency()));
        return new Calculation(
                "DEDUCTIBLE", inputs, steps, amount, null,
                "If this deductible applies, the insured bears the first %s.".formatted(money(amount, facts.currency())));
    }

    private static String describe(Value value, BigDecimal number, String currency) {
        String figure = switch (value.kind()) {
            case "AMOUNT" -> money(number, currency);
            case "PERCENTAGE" -> number.stripTrailingZeros().toPlainString() + "%";
            default -> value.value() + (value.unit() == null ? "" : " " + value.unit().toLowerCase(Locale.ENGLISH));
        };
        return value.name() + ": " + figure + (value.qualifier() == null || value.qualifier().isBlank() ? "" : " " + value.qualifier());
    }

    private static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal amount, String currency) {
        return currency + " " + scale(amount).toPlainString();
    }

    private static String day(LocalDate date) {
        return date.format(DAY);
    }
}
