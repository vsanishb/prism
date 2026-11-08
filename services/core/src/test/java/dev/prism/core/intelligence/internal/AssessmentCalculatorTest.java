package dev.prism.core.intelligence.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.intelligence.internal.AssessmentCalculator.Calculation;
import dev.prism.core.intelligence.internal.AssessmentCalculator.Inputs;
import dev.prism.core.policyknowledge.PublishedKnowledge.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Money, dates, and limits, computed in code from confirmed values (D-029). */
class AssessmentCalculatorTest {

    private static final LocalDate COVER = LocalDate.parse("2025-04-01");

    private static Value value(String kind, String figure, String unit) {
        return new Value(UUID.randomUUID(), UUID.randomUUID(), "Policy value", kind, figure, unit, null);
    }

    private static Inputs inputs(String admission, String discharge, String claimed) {
        return new Inputs(
                Optional.ofNullable(admission).map(LocalDate::parse),
                Optional.ofNullable(discharge).map(LocalDate::parse),
                Optional.ofNullable(claimed).map(BigDecimal::new),
                new BigDecimal("500000.00"),
                "INR",
                COVER);
    }

    private static final Inputs STAY = inputs("2026-03-14", "2026-03-17", "106000.00");

    @Test
    void aWaitingPeriodInMonthsIsDatedFromCoverStart() {
        Calculation result = AssessmentCalculator.calculate("WAITING_PERIOD", value("DURATION", "24", "MONTHS"), STAY);

        assertThat(result.resultDate()).isEqualTo(LocalDate.parse("2027-04-01"));
        assertThat(result.outcome()).contains("falls within the waiting period");
        assertThat(result.steps()).anyMatch(step -> step.startsWith("1 Apr 2025 + 24 months = 1 Apr 2027"));
    }

    @Test
    void anAdmissionOnTheDayAWaitingPeriodEndsIsAfterIt() {
        Calculation result = AssessmentCalculator.calculate(
                "WAITING_PERIOD", value("DURATION", "30", "DAYS"), inputs("2025-05-01", "2025-05-02", "1000.00"));

        assertThat(result.resultDate()).isEqualTo(LocalDate.parse("2025-05-01"));
        assertThat(result.outcome()).contains("is after the waiting period");
    }

    @Test
    void aWaitingPeriodInHoursIsNotDated() {
        Calculation result = AssessmentCalculator.calculate("WAITING_PERIOD", value("DURATION", "24", "HOURS"), STAY);
        assertThat(result.resultDate()).isNull();
        assertThat(result.outcome()).contains("Only periods in days, months, or years");
    }

    @Test
    void anUnknownAdmissionDateGivesTheEndDateButNoComparison() {
        Calculation result = AssessmentCalculator.calculate(
                "WAITING_PERIOD", value("DURATION", "2", "YEARS"), inputs(null, null, "1000.00"));
        assertThat(result.resultDate()).isEqualTo(LocalDate.parse("2027-04-01"));
        assertThat(result.outcome()).contains("admission date is not established");
    }

    @Test
    void anAmountLimitComparesWithTheClaimedAmount() {
        Calculation result = AssessmentCalculator.calculate("AMOUNT_LIMIT", value("AMOUNT", "40000.00", "INR"), STAY);
        assertThat(result.resultAmount()).isEqualByComparingTo("40000.00");
        assertThat(result.outcome()).isEqualTo("The claimed amount is above this limit by INR 66000.00.");
    }

    @Test
    void aDailyPercentageOfSumInsuredIsMultipliedByTheDaysOfStay() {
        Calculation result = AssessmentCalculator.calculate("DAILY_LIMIT", value("PERCENTAGE", "1.00", "PERCENT"), STAY);
        assertThat(result.resultAmount()).isEqualByComparingTo("15000.00");
        assertThat(result.steps()).containsExactly("1% of INR 500000.00 = INR 5000.00 per day", "INR 5000.00 × 3 days = INR 15000.00");
    }

    @Test
    void aSameDayStayCountsAsOneDay() {
        Calculation result = AssessmentCalculator.calculate(
                "DAILY_LIMIT", value("AMOUNT", "2500.00", "INR"), inputs("2026-03-14", "2026-03-14", "5000.00"));
        assertThat(result.resultAmount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void aCopaymentIsAShareOfTheClaimedAmountRoundedToThePaisa() {
        Calculation result = AssessmentCalculator.calculate(
                "COPAYMENT", value("PERCENTAGE", "15.00", "PERCENT"), inputs("2026-03-14", "2026-03-17", "1000.33"));
        assertThat(result.resultAmount()).isEqualByComparingTo("150.05");
        assertThat(result.outcome()).contains("Whether its conditions are met is for the reviewer");
    }

    @Test
    void anUnknownClaimedAmountGivesNoResult() {
        Calculation result = AssessmentCalculator.calculate(
                "COPAYMENT", value("PERCENTAGE", "10.00", "PERCENT"), inputs("2026-03-14", "2026-03-17", null));
        assertThat(result.resultAmount()).isNull();
        assertThat(result.outcome()).contains("not established");
    }

    @Test
    void aPercentOfSumInsuredAndADeductibleAreStatedAsAmounts() {
        assertThat(AssessmentCalculator.calculate("PERCENT_OF_SUM_INSURED", value("PERCENTAGE", "10.00", "PERCENT"), STAY).resultAmount())
                .isEqualByComparingTo("50000.00");
        assertThat(AssessmentCalculator.calculate("DEDUCTIBLE", value("AMOUNT", "25000.00", "INR"), STAY).resultAmount())
                .isEqualByComparingTo("25000.00");
    }

    @Test
    void aValueThatIsNotANumberIsNotComputed() {
        Calculation result = AssessmentCalculator.calculate("AMOUNT_LIMIT", value("AMOUNT", "Up to Sum Insured", null), STAY);
        assertThat(result.resultAmount()).isNull();
        assertThat(result.outcome()).contains("not a number");
    }
}
