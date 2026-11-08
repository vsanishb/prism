package dev.prism.core.risk.internal;

import static dev.prism.core.risk.internal.Facts.BILLS;

import dev.prism.core.risk.Category;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.IndicatorKind;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.Severity;
import dev.prism.core.risk.internal.Rule.Finding;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Documents that disagree with each other or with the details entered for the claim. */
final class ConsistencyRules {

    /** Prescriptions and reports often come from before or after a stay. Beyond these, a date needs a look. */
    private static final int DAYS_BEFORE_ADMISSION = 30;

    private static final int DAYS_AFTER_DISCHARGE = 60;
    private static final Set<String> DATED_DOCUMENTS = Set.of("PRESCRIPTION", "DIAGNOSTIC_REPORT", "OTHER");

    private ConsistencyRules() {}

    static List<Rule> all() {
        return List.of(
                differs("CON-01", "patient_name", Severity.HIGH, "The patient differs across documents",
                        "Whether every document names the same patient as the claim", "The patient's name"),
                differs("CON-02", "hospital_name", Severity.MEDIUM, "The hospital differs across documents",
                        "Whether every document names the same hospital as the claim", "The hospital's name"),
                stayDates(),
                billBeforeAdmission(),
                dischargeBeforeAdmission(),
                datedOutsideStay());
    }

    private static Rule differs(String id, String field, Severity severity, String title, String checks, String subject) {
        return new Rule(
                new RuleDescription(id, 1, Category.CONSISTENCY, IndicatorKind.DATA_INCONSISTENCY, severity, title, checks),
                input -> {
                    List<Finding> found = new ArrayList<>();
                    Facts facts = new Facts(input);
                    facts.claimFact(field).ifPresent(fact -> {
                        if ("CONFLICTING".equals(fact.state())) {
                            found.add(new Finding(subject + " is not the same in every document.", facts.sourceEvidence(fact)));
                        }
                    });
                    Facts.findings(input, "DIFFERS_FROM_ENTERED_DETAILS", field).forEach(finding -> found.add(new Finding(
                            subject + " in the documents differs from the one entered for the claim.",
                            facts.claimFact(field)
                                    .map(fact -> withEntered(facts.sourceEvidence(fact), finding.expected()))
                                    .orElse(List.of()))));
                    return found;
                });
    }

    private static List<Evidence> withEntered(List<Evidence> sources, String entered) {
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("Entered for the claim", entered, null, null, null, null, null));
        evidence.addAll(sources);
        return evidence;
    }

    private static Rule stayDates() {
        return new Rule(
                new RuleDescription("CON-03", 1, Category.CONSISTENCY, IndicatorKind.DATA_INCONSISTENCY, Severity.MEDIUM,
                        "The dates of stay differ across documents",
                        "Whether every document gives the same admission and discharge dates as the claim"),
                input -> {
                    Facts facts = new Facts(input);
                    List<Evidence> evidence = new ArrayList<>();
                    for (String field : List.of("admission_date", "discharge_date")) {
                        facts.claimFact(field)
                                .filter(fact -> "CONFLICTING".equals(fact.state())
                                        || !Facts.findings(input, "DIFFERS_FROM_ENTERED_DETAILS", field).isEmpty())
                                .ifPresent(fact -> evidence.addAll(facts.sourceEvidence(fact)));
                    }
                    return evidence.isEmpty()
                            ? List.of()
                            : List.of(new Finding("The admission or discharge date is not the same in every document.", evidence));
                });
    }

    private static Rule billBeforeAdmission() {
        return new Rule(
                new RuleDescription("CON-04", 1, Category.CONSISTENCY, IndicatorKind.DATA_INCONSISTENCY, Severity.HIGH,
                        "A bill is dated before the admission",
                        "Whether any bill is dated before the patient was admitted"),
                input -> {
                    Facts facts = new Facts(input);
                    LocalDate admission = input.claim().admissionDate();
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
                        facts.documentDate(bill.id(), "bill_date")
                                .filter(date -> date.isBefore(admission))
                                .ifPresent(date -> found.add(new Finding(
                                        "%s is dated %s, before the admission on %s.".formatted(
                                                Wording.document(bill.type()), Wording.date(date), Wording.date(admission)),
                                        facts.documentFactEvidence(bill, "bill_date", "Bill date " + Wording.date(date)))));
                    }
                    return found;
                });
    }

    private static Rule dischargeBeforeAdmission() {
        return new Rule(
                new RuleDescription("CON-05", 1, Category.CONSISTENCY, IndicatorKind.DATA_INCONSISTENCY, Severity.HIGH,
                        "The discharge is before the admission",
                        "Whether the documents put the discharge before the admission"),
                input -> {
                    Facts facts = new Facts(input);
                    return Facts.findings(input, "DISCHARGE_BEFORE_ADMISSION", null).stream()
                            .map(finding -> new Finding(
                                    "The documents give a discharge date before the admission date.",
                                    facts.claimFact("discharge_date").map(facts::sourceEvidence).orElse(List.of())))
                            .toList();
                });
    }

    private static Rule datedOutsideStay() {
        return new Rule(
                new RuleDescription("CON-06", 1, Category.CONSISTENCY, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "A document is dated well outside the stay",
                        "Whether a prescription or report is dated more than %d days before admission or %d days after discharge"
                                .formatted(DAYS_BEFORE_ADMISSION, DAYS_AFTER_DISCHARGE)),
                input -> {
                    Facts facts = new Facts(input);
                    LocalDate earliest = input.claim().admissionDate().minusDays(DAYS_BEFORE_ADMISSION);
                    LocalDate latest = input.claim().dischargeDate().plusDays(DAYS_AFTER_DISCHARGE);
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document document : facts.documentsOf(DATED_DOCUMENTS)) {
                        facts.documentDate(document.id(), "document_date")
                                .filter(date -> date.isBefore(earliest) || date.isAfter(latest))
                                .ifPresent(date -> found.add(new Finding(
                                        "%s is dated %s, well outside the stay.".formatted(
                                                Wording.document(document.type()), Wording.date(date)),
                                        facts.documentFactEvidence(document, "document_date", "Dated " + Wording.date(date)))));
                    }
                    return found;
                });
    }
}
