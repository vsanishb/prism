package dev.prism.core.intelligence.internal;

import static dev.prism.core.intelligence.internal.ExtractionFixtures.amount;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.bill;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.claimForm;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.date;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.discharge;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.item;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.missing;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.result;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.resultClassified;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.text;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.uncertain;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.unverifiedItem;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.aiclient.DocumentExtraction.Result;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.ExtractedDocument;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Finding;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsolidationRulesTest {

    private static final ClaimRecord CLAIM = claim("REIMBURSEMENT", "106000.00");

    private static ClaimRecord claim(String type, String amount) {
        return new ClaimRecord(
                UUID.randomUUID(), type, "PROCESSING", false, "Synthetic Claimant", "Synthetic Patient",
                "Synthetic Hospital", LocalDate.parse("2026-03-14"), LocalDate.parse("2026-03-17"),
                new BigDecimal(amount), "INR", UUID.randomUUID(), UUID.randomUUID(), "CLM-2026-000001", UUID.randomUUID());
    }

    private static ExtractedDocument document(String declared, Result result) {
        return new ExtractedDocument(UUID.randomUUID(), declared, result);
    }

    private static ExtractedDocument document(Result result) {
        return document(result.classification().documentType(), result);
    }

    private static List<ExtractedDocument> documents(Result... results) {
        List<ExtractedDocument> list = new ArrayList<>();
        for (Result result : results) {
            list.add(document(result));
        }
        return list;
    }

    private static Fact fact(List<Fact> facts, String name) {
        return facts.stream()
                .filter(candidate -> candidate.name().equals(name) && candidate.documentId() == null)
                .findFirst()
                .orElseThrow();
    }

    private static List<Finding> findings(ClaimRecord claim, List<ExtractedDocument> documents) {
        List<Fact> facts = FactConsolidator.facts(documents);
        return ClaimValidator.validate(claim, documents, facts, FactConsolidator.lineItems(documents));
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    private static List<ExtractedDocument> consistentSet() {
        return documents(
                claimForm("2026-03-14", "106000.00"),
                bill("106000.00", List.of(item("Room", "6000.00"), item("Surgery", "100000.00"))),
                discharge("2026-03-14"));
    }

    @Test
    void documentsThatAgreeGiveOnePresentFactWithEverySource() {
        Fact admission = fact(FactConsolidator.facts(consistentSet()), "admission_date");

        assertThat(admission.state()).isEqualTo("PRESENT");
        assertThat(admission.value()).isEqualTo("2026-03-14");
        assertThat(admission.sources()).hasSize(3);
    }

    @Test
    void documentsThatDisagreeGiveAConflictWithNoChosenValue() {
        Fact admission = fact(
                FactConsolidator.facts(documents(claimForm("2026-03-14", "106000.00"), discharge("2026-03-15"))),
                "admission_date");

        assertThat(admission.state()).isEqualTo("CONFLICTING");
        assertThat(admission.value()).isNull();
        assertThat(admission.sources()).hasSize(2);
    }

    @Test
    void aFieldNoDocumentStatesIsMissing() {
        Fact diagnosis = fact(FactConsolidator.facts(documents(claimForm("2026-03-14", "1.00"))), "diagnosis");

        assertThat(diagnosis.state()).isEqualTo("MISSING");
        assertThat(diagnosis.sources()).isEmpty();
    }

    @Test
    void anExplicitlyMissingFieldStaysMissing() {
        Result summary = result("DISCHARGE_SUMMARY", List.of(missing("diagnosis", "TEXT", true)), List.of());

        assertThat(fact(FactConsolidator.facts(documents(summary)), "diagnosis").state()).isEqualTo("MISSING");
    }

    @Test
    void onlyUncertainReadingsGiveAnUncertainFactWithNoValue() {
        Result summary = result("DISCHARGE_SUMMARY", List.of(uncertain("diagnosis", "TEXT", "MODEL_UNSURE")), List.of());
        Fact diagnosis = fact(FactConsolidator.facts(documents(summary)), "diagnosis");

        assertThat(diagnosis.state()).isEqualTo("UNCERTAIN");
        assertThat(diagnosis.value()).isNull();
        assertThat(diagnosis.sources()).hasSize(1);
    }

    @Test
    void aClearReadingOutranksAnUncertainOneAndKeepsItsSource() {
        Result clear = result("HOSPITAL_BILL", List.of(date("discharge_date", "2026-03-17")), List.of());
        Result smudged = result("DISCHARGE_SUMMARY", List.of(uncertain("discharge_date", "DATE", "MODEL_UNSURE")), List.of());
        Fact discharge = fact(FactConsolidator.facts(documents(clear, smudged)), "discharge_date");

        assertThat(discharge.state()).isEqualTo("PRESENT");
        assertThat(discharge.sources()).hasSize(2);
    }

    @Test
    void aFullerNameIsTheSameValueAndIsTheOneKept() {
        Fact hospital = fact(
                FactConsolidator.facts(documents(
                        result("CLAIM_FORM", List.of(text("hospital_name", "Synthetic Hospital")), List.of()),
                        result("HOSPITAL_BILL", List.of(text("hospital_name", "Synthetic Hospital, Pune")), List.of()))),
                "hospital_name");

        assertThat(hospital.state()).isEqualTo("PRESENT");
        assertThat(hospital.value()).isEqualTo("Synthetic Hospital, Pune");
    }

    @Test
    void honorificsAndCaseDoNotMakeNamesDiffer() {
        assertThat(FactConsolidator.sameValue("TEXT", "Mrs. Asha Rao", "ASHA RAO")).isTrue();
        assertThat(FactConsolidator.sameValue("TEXT", "Shri Dev Kumar", "Dev Kumar")).isTrue();
    }

    @Test
    void differentPeopleAreNeverTreatedAsTheSame() {
        assertThat(FactConsolidator.sameValue("TEXT", "Asha Rao", "Usha Rao")).isFalse();
        assertThat(FactConsolidator.sameValue("TEXT", "Asha Rao", "Asha")).isTrue();
        assertThat(FactConsolidator.sameValue("TEXT", "Rao Asha", "Asha Rao")).isFalse();
    }

    @Test
    void amountsCompareByValueNotByFormat() {
        assertThat(FactConsolidator.sameValue("AMOUNT", "100.0", "100.00")).isTrue();
        assertThat(FactConsolidator.sameValue("AMOUNT", "100.01", "100.00")).isFalse();
    }

    @Test
    void valuesThatBelongToOneDocumentAreNotComparedAcrossDocuments() {
        List<Fact> facts = FactConsolidator.facts(documents(
                result("HOSPITAL_BILL", List.of(amount("total_amount", "100.00")), List.of()),
                result("ITEMIZED_INVOICE", List.of(amount("total_amount", "25.00")), List.of())));

        assertThat(facts.stream().filter(candidate -> candidate.name().equals("total_amount")))
                .hasSize(2)
                .allSatisfy(total -> {
                    assertThat(total.state()).isEqualTo("PRESENT");
                    assertThat(total.documentId()).isNotNull();
                });
    }

    @Test
    void aConsistentClaimHasNoFindings() {
        assertThat(findings(CLAIM, consistentSet())).isEmpty();
    }

    @Test
    void aReimbursementNeedsAFormABillAndASummary() {
        assertThat(findings(CLAIM, documents(bill("106000.00", List.of()))))
                .filteredOn(finding -> finding.code().equals(ClaimValidator.MISSING_REQUIRED_DOCUMENT))
                .extracting(Finding::expectedValue)
                .containsExactlyInAnyOrder("CLAIM_FORM", "DISCHARGE_SUMMARY");
    }

    @Test
    void aCashlessClaimNeedsNoClaimForm() {
        List<ExtractedDocument> documents = documents(bill("106000.00", List.of()), discharge("2026-03-14"));

        assertThat(codes(findings(claim("CASHLESS", "106000.00"), documents)))
                .doesNotContain(ClaimValidator.MISSING_REQUIRED_DOCUMENT);
    }

    @Test
    void requiredDocumentsUseWhatADocumentReallyIsNotItsLabel() {
        Result mislabelled = resultClassified("DISCHARGE_SUMMARY", "HIGH", "OTHER", List.of(), List.of());
        List<ExtractedDocument> documents = new ArrayList<>(documents(
                claimForm("2026-03-14", "106000.00"), bill("106000.00", List.of())));
        documents.add(document("OTHER", mislabelled));

        List<Finding> findings = findings(CLAIM, documents);
        assertThat(codes(findings)).doesNotContain(ClaimValidator.MISSING_REQUIRED_DOCUMENT).contains("DOCUMENT_TYPE_DIFFERS");
    }

    @Test
    void anUnsureClassificationKeepsTheLabel() {
        Result unsure = resultClassified("DISCHARGE_SUMMARY", "LOW", "OTHER", List.of(), List.of());
        List<ExtractedDocument> documents = List.of(document("OTHER", unsure));

        assertThat(findings(CLAIM, documents))
                .extracting(Finding::expectedValue)
                .contains("DISCHARGE_SUMMARY");
    }

    @Test
    void aConflictIsAWarningThatNamesTheField() {
        List<Finding> findings = findings(CLAIM, documents(claimForm("2026-03-14", "106000.00"), discharge("2026-03-15")));

        assertThat(findings)
                .filteredOn(finding -> finding.code().equals("VALUES_CONFLICT"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.fieldName()).isEqualTo("admission_date");
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                });
    }

    @Test
    void aDischargeBeforeAdmissionIsAnError() {
        Result backwards = result(
                "DISCHARGE_SUMMARY",
                List.of(date("admission_date", "2026-03-17"), date("discharge_date", "2026-03-14")),
                List.of());

        assertThat(findings(CLAIM, documents(backwards)))
                .filteredOn(finding -> finding.code().equals("DISCHARGE_BEFORE_ADMISSION"))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(Severity.ERROR);
    }

    @Test
    void lineItemsThatDoNotAddUpAreReportedWithBothFigures() {
        List<ExtractedDocument> documents = documents(bill("106000.00", List.of(item("Room", "105000.00"))));

        assertThat(findings(CLAIM, documents))
                .filteredOn(finding -> finding.code().equals("LINE_ITEMS_DO_NOT_MATCH_TOTAL"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.expectedValue()).isEqualTo("106000.00");
                    assertThat(finding.actualValue()).isEqualTo("105000.00");
                });
    }

    @Test
    void discountsReduceTheSum() {
        List<ExtractedDocument> documents =
                documents(bill("90.00", List.of(item("Room", "100.00"), item("Discount", "-10.00"))));

        assertThat(codes(findings(claim("CASHLESS", "90.00"), documents))).doesNotContain("LINE_ITEMS_DO_NOT_MATCH_TOTAL");
    }

    @Test
    void anUncertainLineItemIsNotAddedUpAsIfItWereKnown() {
        List<ExtractedDocument> documents =
                documents(bill("106000.00", List.of(item("Room", "1.00"), unverifiedItem("Surgery"))));

        assertThat(codes(findings(CLAIM, documents)))
                .contains("LINE_ITEMS_UNVERIFIED")
                .doesNotContain("LINE_ITEMS_DO_NOT_MATCH_TOTAL");
    }

    @Test
    void valuesThatDifferFromWhatStaffEnteredAreReported() {
        List<Finding> findings = findings(claim("CASHLESS", "106000.00"), documents(discharge("2026-03-10")));

        assertThat(findings)
                .filteredOn(finding -> finding.code().equals("DIFFERS_FROM_ENTERED_DETAILS"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.fieldName()).isEqualTo("admission_date");
                    assertThat(finding.expectedValue()).isEqualTo("2026-03-14");
                    assertThat(finding.actualValue()).isEqualTo("2026-03-10");
                });
    }

    @Test
    void billsAreSummedBeforeComparingWithTheClaimedAmount() {
        List<ExtractedDocument> matching = documents(
                result("HOSPITAL_BILL", List.of(amount("total_amount", "100.00")), List.of()),
                result("ITEMIZED_INVOICE", List.of(amount("total_amount", "25.00")), List.of()));

        assertThat(codes(findings(claim("CASHLESS", "125.00"), matching))).doesNotContain("CLAIMED_AMOUNT_DIFFERS_FROM_BILLS");
        assertThat(codes(findings(claim("CASHLESS", "130.00"), matching))).contains("CLAIMED_AMOUNT_DIFFERS_FROM_BILLS");
    }

    @Test
    void aBillWithNoStatedTotalIsReportedAndNotSummedAsZero() {
        Result noTotal = result("HOSPITAL_BILL", List.of(missing("total_amount", "AMOUNT", true)), List.of());

        assertThat(codes(findings(claim("CASHLESS", "130.00"), documents(noTotal))))
                .contains("VALUE_MISSING")
                .doesNotContain("CLAIMED_AMOUNT_DIFFERS_FROM_BILLS");
    }

    @Test
    void aCriticalUncertainValueIsReported() {
        Result smudged = result("HOSPITAL_BILL", List.of(uncertain("total_amount", "AMOUNT", "REREAD_DISAGREES")), List.of());

        assertThat(codes(findings(CLAIM, documents(smudged)))).contains("VALUE_UNCERTAIN");
    }

    @Test
    void findingsNeverUseTheLanguageOfDecisions() {
        List<Finding> findings = findings(CLAIM, documents(bill("1.00", List.of(item("Room", "2.00")))));

        assertThat(codes(findings)).noneMatch(code -> code.contains("FRAUD") || code.contains("REJECT") || code.contains("DENY"));
    }
}
