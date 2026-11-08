package dev.prism.core.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.risk.RiskInput.OtherBill;
import dev.prism.core.risk.RiskInput.SharedFile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every rule, with a case that raises it and a case that does not. Rules are pure, so no database is needed. */
class RuleEngineTest {

    private static List<String> rules(RiskInput input) {
        return RuleEngine.evaluate(input).stream().map(Indicator::ruleId).toList();
    }

    private static Indicator only(RiskInput input, String ruleId) {
        List<Indicator> found = RuleEngine.evaluate(input).stream().filter(indicator -> indicator.ruleId().equals(ruleId)).toList();
        assertThat(found).as(ruleId).hasSize(1);
        return found.getFirst();
    }

    @Test
    void aConsistentClaimRaisesNothing() {
        assertThat(RuleEngine.evaluate(RiskInputs.clean().build())).isEmpty();
    }

    @Test
    void everyRuleHasAnIdVersionAndPlainDescription() {
        assertThat(RuleEngine.rules()).hasSize(22);
        assertThat(RuleEngine.rules()).extracting(RuleDescription::id).doesNotHaveDuplicates();
        assertThat(RuleEngine.rules()).allSatisfy(rule -> {
            assertThat(rule.version()).isPositive();
            assertThat(rule.title()).isNotBlank();
            assertThat(rule.checks()).startsWith("Whether");
            assertThat(rule.category()).isNotEqualTo(Category.STATISTICAL);
        });
    }

    // --- Consistency ---

    @Test
    void conflictingPatientsAcrossDocumentsAreAnInconsistency() {
        RiskInputs inputs = RiskInputs.clean().claimFactState("patient_name", "CONFLICTING", "Synthetic Patient Vikram",
                "Synthetic Patient Meenal");
        Indicator indicator = only(inputs.build(), "CON-01");
        assertThat(indicator.kind()).isEqualTo(IndicatorKind.DATA_INCONSISTENCY);
        assertThat(indicator.severity()).isEqualTo(Severity.HIGH);
        assertThat(indicator.evidence()).extracting(Evidence::detail)
                .containsExactly("Reads: Synthetic Patient Vikram", "Reads: Synthetic Patient Meenal");
        // The explanation is summarized by a model, so it never carries the names.
        assertThat(indicator.explanation()).doesNotContain("Vikram", "Meenal");
    }

    @Test
    void aPatientDifferingFromTheEnteredDetailsIsAnInconsistency() {
        RiskInputs inputs = RiskInputs.clean()
                .finding("DIFFERS_FROM_ENTERED_DETAILS", "patient_name", null, "Synthetic Patient Vikram", "Synthetic Patient Vikas");
        assertThat(only(inputs.build(), "CON-01").evidence().getFirst().detail()).isEqualTo("Synthetic Patient Vikram");
    }

    @Test
    void conflictingHospitalsAndStayDatesAreInconsistencies() {
        RiskInputs inputs = RiskInputs.clean()
                .claimFactState("hospital_name", "CONFLICTING", "A", "B")
                .claimFactState("admission_date", "CONFLICTING", "2026-07-07", "2026-07-01");
        assertThat(rules(inputs.build())).containsExactly("CON-02", "CON-03");
    }

    @Test
    void aBillDatedBeforeTheAdmissionIsRaisedButNotOneOnTheDay() {
        RiskInputs before = RiskInputs.clean();
        before.documentFact(before.bill, "bill_date", "2026-07-06");
        assertThat(only(before.build(), "CON-04").explanation()).isEqualTo(
                "Hospital bill is dated 6 Jul 2026, before the admission on 7 Jul 2026.");

        RiskInputs sameDay = RiskInputs.clean();
        sameDay.documentFact(sameDay.bill, "bill_date", "2026-07-07");
        assertThat(rules(sameDay.build())).isEmpty();
    }

    @Test
    void aDischargeBeforeTheAdmissionIsRaised() {
        RiskInputs inputs = RiskInputs.clean().finding("DISCHARGE_BEFORE_ADMISSION", "discharge_date", null, null, null);
        assertThat(rules(inputs.build())).containsExactly("CON-05");
    }

    @Test
    void aPrescriptionLongBeforeTheStayIsRaisedButNotOneJustBefore() {
        RiskInputs far = RiskInputs.clean();
        far.documentFact(far.addDocument("PRESCRIPTION"), "document_date", "2026-05-01");
        assertThat(only(far.build(), "CON-06").severity()).isEqualTo(Severity.LOW);

        RiskInputs near = RiskInputs.clean();
        near.documentFact(near.addDocument("PRESCRIPTION"), "document_date", "2026-06-20");
        assertThat(rules(near.build())).isEmpty();
    }

    // --- Financial ---

    @Test
    void itemsThatDoNotAddUpAreAnInconsistencyWithBothAmounts() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.finding("LINE_ITEMS_DO_NOT_MATCH_TOTAL", "total_amount", inputs.bill, "380000.00", "310000.00");
        assertThat(only(inputs.build(), "FIN-01").explanation())
                .isEqualTo("Hospital bill states a total of INR 3,80,000.00, but its line items add up to INR 3,10,000.00.");
    }

    @Test
    void aRepeatedLineItemIsRaisedOnlyWithinOneBill() {
        RiskInputs repeated = RiskInputs.clean();
        repeated.item(repeated.bill, "ICU charges", "45000.00").item(repeated.bill, "icu  CHARGES", "45000.00");
        assertThat(only(repeated.build(), "FIN-02").evidence()).hasSize(2);

        RiskInputs acrossBills = RiskInputs.clean();
        UUID invoice = acrossBills.addDocument("ITEMIZED_INVOICE");
        acrossBills.item(acrossBills.bill, "ICU charges", "45000.00").item(invoice, "ICU charges", "45000.00");
        assertThat(rules(acrossBills.build())).doesNotContain("FIN-02");
    }

    @Test
    void aClaimAboveTheBillsIsRaisedBeyondRounding() {
        RiskInputs above = RiskInputs.clean();
        above.claimed = new BigDecimal("110000.00");
        assertThat(only(above.build(), "FIN-03").explanation()).isEqualTo(
                "The claim asks for INR 1,10,000.00, but the bills total INR 98,400.00.");

        RiskInputs rounding = RiskInputs.clean();
        rounding.claimed = new BigDecimal("98500.00");
        assertThat(rules(rounding.build())).isEmpty();
    }

    @Test
    void billsAreNotComparedWhenOneStatesNoTotal() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.claimed = new BigDecimal("150000.00");
        inputs.documentMissing(inputs.bill, "total_amount");
        assertThat(rules(inputs.build())).doesNotContain("FIN-03");
    }

    @Test
    void theSumInsuredIsComparedAboveAndNearIt() {
        RiskInputs above = RiskInputs.clean();
        above.claimed = new BigDecimal("520000.00");
        above.documentAmount(above.bill, "total_amount", "520000.00");
        assertThat(rules(above.build())).contains("FIN-04").doesNotContain("FIN-05");

        RiskInputs near = RiskInputs.clean();
        near.claimed = new BigDecimal("470000.00");
        near.documentAmount(near.bill, "total_amount", "470000.00");
        assertThat(only(near.build(), "FIN-05").explanation()).isEqualTo("The claim asks for 94% of the sum insured.");

        RiskInputs well = RiskInputs.clean();
        well.claimed = new BigDecimal("420000.00");
        well.documentAmount(well.bill, "total_amount", "420000.00");
        assertThat(rules(well.build())).doesNotContain("FIN-04", "FIN-05");
    }

    @Test
    void aVeryHighDailyAmountIsRaisedOnlyInRupees() {
        RiskInputs high = RiskInputs.clean();
        high.claimed = new BigDecimal("300000.00");
        high.dischargeDate = high.admission.plusDays(1);
        high.documentAmount(high.bill, "total_amount", "300000.00");
        assertThat(only(high.build(), "FIN-06").explanation()).isEqualTo("The claim asks for INR 3,00,000.00 a day, over 1 day of stay.");

        high.currency = "USD";
        high.policyCurrency = "USD";
        assertThat(rules(high.build())).doesNotContain("FIN-06");
    }

    // --- Duplicates ---

    @Test
    void theSameFileInAnotherClaimIsAFraudIndicatorWithoutItsContent() {
        RiskInputs inputs = RiskInputs.clean();
        UUID other = UUID.randomUUID();
        inputs.sharedFiles.add(new SharedFile(inputs.bill, other, "CLM-2026-000042", UUID.randomUUID()));
        Indicator indicator = only(inputs.build(), "DUP-01");
        assertThat(indicator.kind()).isEqualTo(IndicatorKind.FRAUD_INDICATOR);
        assertThat(indicator.evidence()).extracting(Evidence::matchedClaimId).contains(other);
        assertThat(indicator.evidence()).extracting(Evidence::detail).containsExactly("Identical file (SHA-256)", "CLM-2026-000042");
    }

    @Test
    void aFileReusedInManyClaimsIsOneIndicatorNamingThemAll() {
        // Regression: version 1 raised one indicator per copy, so a widely reused file alone made a claim high risk.
        RiskInputs inputs = RiskInputs.clean();
        for (int index = 1; index <= 3; index++) {
            inputs.sharedFiles.add(new SharedFile(inputs.bill, UUID.randomUUID(), "CLM-2026-00004" + index, UUID.randomUUID()));
        }
        Indicator indicator = only(inputs.build(), "DUP-01");
        assertThat(indicator.ruleVersion()).isEqualTo(2);
        assertThat(indicator.explanation()).isEqualTo("Hospital bill is the same file as a document in 3 other claims.");
        assertThat(indicator.evidence()).extracting(Evidence::matchedClaimId).filteredOn(java.util.Objects::nonNull).hasSize(3);
    }

    @Test
    void overlappingStaysAndSimilarClaimsAreOneIndicatorEach() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-07-08", "2026-07-09", "1.00");
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-07-06", "2026-07-08", "1.00");
        Indicator overlapping = only(inputs.build(), "DUP-04");
        assertThat(overlapping.explanation()).isEqualTo("2 other claims for the same patient cover stays overlapping this one.");
        assertThat(overlapping.evidence()).hasSize(2);

        RiskInputs similar = RiskInputs.clean();
        similar.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-06-15", "2026-06-19", "95000.00");
        similar.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-07-20", "2026-07-22", "99000.00");
        assertThat(only(similar.build(), "DUP-05").evidence()).hasSize(2);
    }

    @Test
    void anInvoiceNumberReusedAtTheSameHospitalIsRaised() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.otherBills.add(new OtherBill(UUID.randomUUID(), "CLM-2026-000007", UUID.randomUUID(),
                "SYNTHETIC RIVERSIDE HOSPITAL KOCHI (FICTIONAL)", "gf 2026/05521", List.of()));
        assertThat(rules(inputs.build())).containsExactly("DUP-02");
    }

    @Test
    void theSameInvoiceNumberAtAnotherHospitalIsNotRaised() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.otherBills.add(new OtherBill(UUID.randomUUID(), "CLM-2026-000007", UUID.randomUUID(),
                "Synthetic Lotus Hospital, Nagpur (Fictional)", "GF-2026-05521", List.of()));
        assertThat(rules(inputs.build())).isEmpty();
    }

    @Test
    void aBillPairAlreadyKnownAsTheSameFileIsNotCountedAgain() {
        RiskInputs inputs = RiskInputs.clean();
        UUID otherClaim = UUID.randomUUID();
        UUID otherDocument = UUID.randomUUID();
        inputs.sharedFiles.add(new SharedFile(inputs.bill, otherClaim, "CLM-2026-000007", otherDocument));
        inputs.otherBills.add(new OtherBill(otherClaim, "CLM-2026-000007", otherDocument, RiskInputs.HOSPITAL, "GF-2026-05521",
                inputs.billItemKeys()));
        assertThat(rules(inputs.build())).containsExactly("DUP-01");
    }

    @Test
    void aBillWithMostItemsMatchingAnotherClaimsBillIsRaised() {
        RiskInputs inputs = RiskInputs.clean();
        List<RiskInput.ItemKey> others = new ArrayList<>(inputs.billItemKeys().subList(0, 5));
        others.add(new RiskInput.ItemKey("Room upgrade", new BigDecimal("4000.00")));
        others.add(new RiskInput.ItemKey("Pharmacy and consumables", new BigDecimal("9100.00")));
        inputs.otherBills.add(new OtherBill(UUID.randomUUID(), "CLM-2026-000011", UUID.randomUUID(), RiskInputs.HOSPITAL,
                "OTHER-1", others));
        Indicator indicator = only(inputs.build(), "DUP-03");
        assertThat(indicator.explanation()).startsWith("5 of 7 line items on the hospital bill match");
        assertThat(indicator.evidence()).hasSize(6);
    }

    @Test
    void aBillWithFewItemsMatchingIsNotRaised() {
        RiskInputs inputs = RiskInputs.clean();
        List<RiskInput.ItemKey> others = new ArrayList<>(inputs.billItemKeys().subList(0, 4));
        others.add(new RiskInput.ItemKey("Different", new BigDecimal("1.00")));
        others.add(new RiskInput.ItemKey("Different", new BigDecimal("2.00")));
        others.add(new RiskInput.ItemKey("Different", new BigDecimal("3.00")));
        inputs.otherBills.add(new OtherBill(UUID.randomUUID(), "CLM-2026-000011", UUID.randomUUID(), RiskInputs.HOSPITAL,
                "OTHER-1", others));
        assertThat(rules(inputs.build())).isEmpty();
    }

    @Test
    void anOverlappingStayForTheSamePatientIsRaised() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.otherClaim("Mr Synthetic Patient Vikram", "Elsewhere", "2026-07-09", "2026-07-12", "20000.00");
        assertThat(rules(inputs.build())).containsExactly("DUP-04");
    }

    @Test
    void aSimilarClaimShortlyBeforeIsRaisedOnlyWhenEverythingMatches() {
        RiskInputs similar = RiskInputs.clean();
        similar.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-06-15", "2026-06-19", "95000.00");
        assertThat(only(similar.build(), "DUP-05").explanation())
                .isEqualTo("Another claim for the same patient at the same hospital, 18 days apart, is for a similar amount.");

        RiskInputs otherAmount = RiskInputs.clean();
        otherAmount.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-06-15", "2026-06-19", "40000.00");
        RiskInputs otherHospital = RiskInputs.clean();
        otherHospital.otherClaim(RiskInputs.PATIENT, "Synthetic Lotus Hospital", "2026-06-15", "2026-06-19", "95000.00");
        RiskInputs longAgo = RiskInputs.clean();
        longAgo.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-03-01", "2026-03-04", "95000.00");
        RiskInputs otherPatient = RiskInputs.clean();
        otherPatient.otherClaim("Synthetic Patient Nisha", RiskInputs.HOSPITAL, "2026-06-15", "2026-06-19", "95000.00");
        for (RiskInputs negative : List.of(otherAmount, otherHospital, longAgo, otherPatient)) {
            assertThat(rules(negative.build())).isEmpty();
        }
    }

    // --- Documents and missing information ---

    @Test
    void documentAnomaliesAreRaised() {
        RiskInputs inputs = RiskInputs.clean();
        inputs.finding("DOCUMENT_TYPE_DIFFERS", null, inputs.discharge, "DISCHARGE_SUMMARY", "PRESCRIPTION");
        inputs.documentMissing(inputs.bill, "bill_number");
        UUID invoice = inputs.addDocument("ITEMIZED_INVOICE");
        inputs.documentFact(invoice, "bill_number", "X-1").documentFact(invoice, "bill_date", "2026-07-10")
                .documentAmount(invoice, "total_amount", "1.00");
        assertThat(rules(inputs.build())).containsExactly("DOC-01", "DOC-02", "DOC-03");
        assertThat(only(inputs.build(), "DOC-02").explanation()).isEqualTo("Hospital bill does not state its bill number.");
    }

    @Test
    void missingDocumentsAndUnestablishedFactsAreRaisedAsRiskIndicators() {
        RiskInputs inputs = RiskInputs.clean()
                .finding("MISSING_REQUIRED_DOCUMENT", null, null, "DISCHARGE_SUMMARY", null)
                .claimFactState("diagnosis", "MISSING");
        assertThat(rules(inputs.build())).containsExactly("MIS-01", "MIS-02");
        assertThat(only(inputs.build(), "MIS-02").explanation()).isEqualTo("The documents do not clearly state the diagnosis.");
        assertThat(RuleEngine.evaluate(inputs.build())).allSatisfy(indicator ->
                assertThat(indicator.kind()).isEqualTo(IndicatorKind.RISK_INDICATOR));
    }

    // --- Properties of the whole ruleset ---

    @Test
    void evaluationIsDeterministic() {
        RiskInputs inputs = severe();
        RiskInput input = inputs.build();
        assertThat(RuleEngine.evaluate(input)).isEqualTo(RuleEngine.evaluate(input));
    }

    private static final Pattern ACCUSATION = Pattern.compile(
            "fraudulent|fraudster|confirmed fraud|fraud (was|is|has been) (confirmed|proven|committed)|committed fraud|guilty|"
                    + "\\breject|\\bden(y|ied)|\\bdecline|\\bapprov|\\bpayable|\\bsuspicious person|\\bliar|\\bcriminal");

    @Test
    void noRuleTextAccusesOrDecides() {
        List<String> texts = new ArrayList<>();
        RuleEngine.rules().forEach(rule -> {
            texts.add(rule.title());
            texts.add(rule.checks());
        });
        RuleEngine.evaluate(severe().build()).forEach(indicator -> {
            texts.add(indicator.title());
            texts.add(indicator.explanation());
        });
        assertThat(texts).isNotEmpty().allSatisfy(text ->
                assertThat(ACCUSATION.matcher(text.toLowerCase(Locale.ROOT)).find()).as(text).isFalse());
    }

    /** As many rules as possible at once, for the properties above. */
    private static RiskInputs severe() {
        RiskInputs inputs = RiskInputs.clean()
                .claimFactState("patient_name", "CONFLICTING", "A", "B")
                .finding("LINE_ITEMS_DO_NOT_MATCH_TOTAL", "total_amount", null, "1.00", "2.00");
        inputs.claimed = new BigDecimal("740000.00");
        inputs.dischargeDate = inputs.admission.plusDays(1);
        inputs.documentFact(inputs.bill, "bill_date", LocalDate.parse("2026-07-01").toString());
        inputs.item(inputs.bill, "ICU", "100.00").item(inputs.bill, "ICU", "100.00");
        inputs.sharedFiles.add(new SharedFile(inputs.bill, UUID.randomUUID(), "CLM-2026-000001", UUID.randomUUID()));
        inputs.otherClaim(RiskInputs.PATIENT, RiskInputs.HOSPITAL, "2026-07-06", "2026-07-08", "700000.00");
        return inputs;
    }
}
