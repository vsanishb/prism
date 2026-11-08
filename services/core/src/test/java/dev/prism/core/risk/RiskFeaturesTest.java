package dev.prism.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The features the detectors see. The disguised demonstration's values are pinned here and in the AI
 * service's tests, so a change on either side that would alter the demonstration fails a test.
 */
class RiskFeaturesTest {

    @Test
    void featuresComeInTheDetectorsOrder() {
        assertThat(RiskFeatures.compute(RiskInputs.clean().build()).keySet()).containsExactly(
                "claimedAmount", "sumInsuredRatio", "lengthOfStayDays", "amountPerDay", "lineItemCount", "largestItemShare",
                "billDeviation", "priorClaims12m", "daysSinceCoverStart");
    }

    @Test
    void theDisguisedDemonstrationGivesItsRecordedFeatures() {
        RiskInputs inputs = new RiskInputs();
        inputs.documents.add(new RiskInput.Document(inputs.bill, "HOSPITAL_BILL"));
        inputs.admission = LocalDate.parse("2026-09-18");
        inputs.dischargeDate = LocalDate.parse("2026-09-20");
        inputs.claimed = new BigDecimal("392000.00");
        inputs.documentAmount(inputs.bill, "total_amount", "392000.00");
        for (String amount : List.of("17000", "165000", "38000", "72000", "54000", "36500", "9500")) {
            inputs.item(inputs.bill, "Item " + amount, amount);
        }
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-04-12", "2026-04-15", "46800.00");
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-08-28", "2026-08-31", "384500.00");

        Map<String, Double> features = RiskFeatures.compute(inputs.build());
        assertThat(features.get("claimedAmount")).isEqualTo(392000.0);
        assertThat(features.get("sumInsuredRatio")).isCloseTo(0.784, within(1e-9));
        assertThat(features.get("lengthOfStayDays")).isEqualTo(2.0);
        assertThat(features.get("amountPerDay")).isEqualTo(196000.0);
        assertThat(features.get("lineItemCount")).isEqualTo(7.0);
        assertThat(features.get("largestItemShare")).isCloseTo(0.4209, within(1e-4));
        assertThat(features.get("billDeviation")).isCloseTo(0.0, within(1e-12));
        assertThat(features.get("priorClaims12m")).isEqualTo(2.0);
        assertThat(features.get("daysSinceCoverStart")).isEqualTo(260.0);
    }

    @Test
    void whatTheFactsCannotGiveIsLeftUnknown() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.items.clear();
        inputs.documentMissing(inputs.bill, "total_amount");
        inputs.sumInsured = null;
        inputs.coverStart = null;
        Map<String, Double> features = RiskFeatures.compute(inputs.build());
        assertThat(features.get("lineItemCount")).isNull();
        assertThat(features.get("largestItemShare")).isNull();
        assertThat(features.get("billDeviation")).isNull();
        assertThat(features.get("sumInsuredRatio")).isNull();
        assertThat(features.get("daysSinceCoverStart")).isNull();
    }

    @Test
    void priorClaimsCountOnlyTheSamePatientInTheYearBefore() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.otherClaim("Synthetic Patient VIKRAM", RiskInputs.HOSPITAL, "2025-07-08", "2025-07-09", "1.00");
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2025-07-06", "2025-07-07", "1.00");
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-08-01", "2026-08-02", "1.00");
        inputs.otherClaim("Synthetic Patient Nisha", RiskInputs.HOSPITAL, "2026-03-01", "2026-03-02", "1.00");
        assertThat(RiskFeatures.priorClaims(inputs.build())).isEqualTo(1);
    }

    @Test
    void aDayCareStayCountsAsOneDayForTheDailyAmount() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.dischargeDate = inputs.admission;
        Map<String, Double> features = RiskFeatures.compute(inputs.build());
        assertThat(features.get("lengthOfStayDays")).isZero();
        assertThat(features.get("amountPerDay")).isEqualTo(98400.0);
    }

    @Test
    void onlyBillsLineItemsCount() {
        RiskInputs inputs = RiskInputs.clean();
        UUID pharmacy = inputs.addDocument("PRESCRIPTION");
        inputs.item(pharmacy, "Should not count", "999999.00");
        assertThat(RiskFeatures.compute(inputs.build()).get("lineItemCount")).isEqualTo(7.0);
    }
}
