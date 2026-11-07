package dev.prism.core.policyknowledge.internal;

import static dev.prism.core.policyknowledge.internal.PolicyFixtures.amount;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.batch;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.cut;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.goldWording;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.provision;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.reference;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.uncertain;
import static dev.prism.core.policyknowledge.internal.PolicyFixtures.value;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.policyknowledge.internal.Assembly.Finding;
import dev.prism.core.policyknowledge.internal.Assembly.Provision;
import dev.prism.core.policyknowledge.internal.Assembly.Relation;
import dev.prism.core.policyknowledge.internal.Assembly.Severity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyAssemblyRulesTest {

    private static final BigDecimal SUM_INSURED = new BigDecimal("500000.00");

    private static List<Finding> validate(List<PolicyExtraction.Result> batches, int pageCount) {
        List<Provision> provisions = PolicyAssembler.provisions(batches);
        List<int[]> read = batches.stream().map(batch -> new int[] {batch.firstPage(), batch.lastPage()}).toList();
        return PolicyValidator.validate(provisions, PolicyAssembler.relations(provisions), pageCount, read, SUM_INSURED);
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    @Test
    void batchesAreSixPagesAndOverlapByOne() {
        assertThat(PolicyAssembler.batches(2)).containsExactly(new int[] {1, 2});
        assertThat(PolicyAssembler.batches(6)).containsExactly(new int[] {1, 6});
        assertThat(PolicyAssembler.batches(14))
                .containsExactly(new int[] {1, 6}, new int[] {6, 11}, new int[] {11, 14});
    }

    @Test
    void aProvisionSeenInTwoOverlappingBatchesIsKeptOnceWithTheFullerText() {
        var partial = provision("4.5", "EXCLUSION", "4.5 Treatment outside India is not covered.", 6, List.of());
        var whole = provision("4.5", "EXCLUSION",
                "4.5 Treatment outside India is not covered. It does not apply to emergency evacuation within 48 hours.", 6,
                List.of(value("Window", "DURATION", "48", "HOURS")));

        List<Provision> provisions = PolicyAssembler.provisions(List.of(batch(1, 6, 7, partial), batch(6, 7, 7, whole)));

        assertThat(provisions).singleElement().satisfies(provision -> {
            assertThat(provision.text).contains("emergency evacuation");
            assertThat(provision.values).hasSize(1);
        });
    }

    @Test
    void aClauseCutAtABatchEndIsJoinedToItsContinuation() {
        var start = cut(provision("4.5", "EXCLUSION", "4.5 Treatment outside India is not covered.", 6, List.of()), true, false);
        var rest = cut(provision(null, "EXCLUSION", "It does not apply to emergency evacuation.", 7, List.of()), false, true);

        List<Provision> provisions = PolicyAssembler.provisions(List.of(batch(1, 6, 7, start), batch(7, 7, 7, rest)));

        assertThat(provisions).singleElement().satisfies(provision -> {
            assertThat(provision.text).isEqualTo("4.5 Treatment outside India is not covered. It does not apply to emergency evacuation.");
            assertThat(provision.pageStart).isEqualTo(6);
            assertThat(provision.pageEnd).isEqualTo(7);
        });
    }

    @Test
    void aContinuationIsNotJoinedToAProvisionThatWasNotCut() {
        var whole = provision("4.4", "EXCLUSION", "4.4 Dental treatment.", 6, List.of());
        var orphan = cut(provision(null, "CLAUSE", "Stray continuation text.", 7, List.of()), false, true);

        assertThat(PolicyAssembler.provisions(List.of(batch(1, 6, 7, whole), batch(7, 7, 7, orphan)))).hasSize(2);
    }

    @Test
    void provisionsAreInDocumentOrderWithNumericKeys() {
        var ten = provision("2.10", "BENEFIT", "2.10 Ambulance", 1, List.of());
        var nine = provision("2.9", "BENEFIT", "2.9 Newborn", 1, List.of());

        assertThat(PolicyAssembler.provisions(List.of(batch(1, 1, 1, ten, nine))))
                .extracting(provision -> provision.key)
                .containsExactly("2.9", "2.10");
    }

    @Test
    void relationsComeFromTheModelFromCitationsAndFromDefinedTerms() {
        List<Provision> provisions = PolicyAssembler.provisions(List.of(goldWording()));
        List<Relation> relations = PolicyAssembler.relations(provisions);

        assertThat(relations)
                .extracting(relation -> relation.from().key + ">" + relation.targetKey() + ":" + relation.relation() + ":" + relation.origin())
                .contains("2.1>1.1:QUALIFIES:MODEL", "2.6>3.2:QUALIFIES:MODEL")
                .doesNotContain("2.1>1.1:REFERENCES:RULE");
        assertThat(relations).allSatisfy(relation -> assertThat(relation.to()).isNotNull());
    }

    @Test
    void aCitationTheModelMissedIsFoundByCode() {
        var limit = provision("2.2", "BENEFIT", "2.2 Room rent as limited by Clause 2.9", 1, List.of());
        var target = provision("2.9", "BENEFIT", "2.9 Room categories", 1, List.of());

        List<Relation> relations = PolicyAssembler.relations(PolicyAssembler.provisions(List.of(batch(1, 1, 1, limit, target))));

        assertThat(relations).singleElement().satisfies(relation -> {
            assertThat(relation.relation()).isEqualTo("REFERENCES");
            assertThat(relation.origin()).isEqualTo("RULE");
        });
    }

    @Test
    void aDefinedTermUsedElsewhereLinksToItsDefinition() {
        var definition = provision("1.2", "DEFINITION", "Day Care Treatment", "1.2 Day Care Treatment means...", 1, List.of(),
                List.of());
        var user = provision("2.6", "BENEFIT", "2.6 Day care treatment up to the Sum Insured", 1, List.of());

        List<Relation> relations =
                PolicyAssembler.relations(PolicyAssembler.provisions(List.of(batch(1, 1, 1, definition, user))));

        assertThat(relations).singleElement().satisfies(relation -> {
            assertThat(relation.from().key).isEqualTo("2.6");
            assertThat(relation.relation()).isEqualTo("DEFINES");
        });
    }

    @Test
    void aConsistentWordingHasNoBlockingFindings() {
        assertThat(validate(List.of(goldWording()), 2)).noneMatch(finding -> finding.severity() == Severity.ERROR);
    }

    @Test
    void unreadPagesAreAnError() {
        assertThat(validate(List.of(goldWording()), 5))
                .filteredOn(finding -> finding.code().equals("PAGES_NOT_READ"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.actualValue()).isEqualTo("[3, 4, 5]"));
    }

    @Test
    void aScheduleSumInsuredThatDiffersFromTheRegisterIsAnError() {
        var schedule = provision("", "SCHEDULE", "Sum Insured", "Sum Insured INR 7,50,000", 1,
                List.of(amount("Sum insured", "750000.00")), List.of());

        assertThat(codes(validate(List.of(batch(1, 1, 1, schedule)), 1))).contains("SUM_INSURED_DIFFERS_FROM_REGISTER");
    }

    @Test
    void aLimitAboveTheSumInsuredIsAnError() {
        var benefit = provision("2.1", "BENEFIT", "2.1 Organ donor INR 9,00,000", 1, List.of(amount("Limit", "900000.00")));

        assertThat(codes(validate(List.of(batch(1, 1, 1, benefit)), 1))).contains("LIMIT_EXCEEDS_SUM_INSURED");
    }

    @Test
    void aPercentageAboveAHundredIsAnError() {
        var benefit = provision("2.1", "BENEFIT", "2.1 Room rent 150%", 1, List.of(value("Limit", "PERCENTAGE", "150.00", "PERCENT")));

        assertThat(codes(validate(List.of(batch(1, 1, 1, benefit)), 1))).contains("PERCENTAGE_OVER_100");
    }

    @Test
    void anUncertainCriticalValueIsAWarning() {
        var benefit = provision("2.1", "BENEFIT", "2.1 Ambulance", 1, List.of(uncertain("Limit", "AMOUNT")));

        assertThat(validate(List.of(batch(1, 1, 1, benefit)), 1))
                .filteredOn(finding -> finding.code().equals("VALUE_UNCERTAIN"))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(Severity.WARNING);
    }

    @Test
    void aMissingClauseNumberIsAGap() {
        var first = provision("4.1", "EXCLUSION", "4.1 Cosmetic", 1, List.of());
        var third = provision("4.3", "EXCLUSION", "4.3 Dental", 1, List.of());

        assertThat(validate(List.of(batch(1, 1, 1, first, third)), 1))
                .filteredOn(finding -> finding.code().equals("NUMBERING_GAP"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.expectedValue()).isEqualTo("4.2"));
    }

    @Test
    void anExclusionWithAnUncapturedExceptionIsReported() {
        var exclusion = provision("4.1", "EXCLUSION", "4.1 Cosmetic treatment, except after an accident", 1, List.of());

        assertThat(codes(validate(List.of(batch(1, 1, 1, exclusion)), 1))).contains("EXCEPTION_NOT_CAPTURED");
    }

    @Test
    void aCitationOfAMissingClauseIsUnresolved() {
        var benefit = provision("2.1", "BENEFIT", "In-patient", "2.1 Subject to Clause 9.9", 1, List.of(),
                List.of(reference("9.9", "QUALIFIES")));

        assertThat(validate(List.of(batch(1, 1, 1, benefit)), 1))
                .filteredOn(finding -> finding.code().equals("UNRESOLVED_REFERENCE"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.expectedValue()).isEqualTo("9.9"));
    }

    @Test
    void aWaitingPeriodWithoutADurationIsReported() {
        var waiting = provision("3.1", "WAITING_PERIOD", "3.1 A waiting period applies", 1, List.of());

        assertThat(codes(validate(List.of(batch(1, 1, 1, waiting)), 1))).contains("WAITING_PERIOD_WITHOUT_DURATION");
    }

    @Test
    void theSameRuleJudgesACorrection() {
        assertThat(PolicyValidator.violation("SCHEDULE", "Sum Insured", "Sum insured", "AMOUNT", new BigDecimal("500000"), SUM_INSURED))
                .isEmpty();
        assertThat(PolicyValidator.violation("BENEFIT", "Room", "Limit", "PERCENTAGE", new BigDecimal("2"), SUM_INSURED)).isEmpty();
        assertThat(PolicyValidator.violation("BENEFIT", "Room", "Limit", "DURATION", new BigDecimal("900000"), SUM_INSURED)).isEmpty();
    }
}
