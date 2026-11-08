package dev.prism.core.risk.internal;

import dev.prism.core.risk.Category;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.IndicatorKind;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.Severity;
import dev.prism.core.risk.internal.Rule.Finding;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What the claim lacks. Missing information is a reason to ask, never a reason to suspect. */
final class MissingInformationRules {

    private static final List<Map.Entry<String, String>> CRITICAL = List.of(
            Map.entry("policy_number", "policy number"),
            Map.entry("patient_name", "patient"),
            Map.entry("hospital_name", "hospital"),
            Map.entry("admission_date", "admission date"),
            Map.entry("discharge_date", "discharge date"),
            Map.entry("claimed_amount", "claimed amount"),
            Map.entry("diagnosis", "diagnosis"));

    private MissingInformationRules() {}

    static List<Rule> all() {
        return List.of(requiredDocument(), criticalFacts());
    }

    private static Rule requiredDocument() {
        return new Rule(
                new RuleDescription("MIS-01", 1, Category.MISSING_INFORMATION, IndicatorKind.RISK_INDICATOR, Severity.MEDIUM,
                        "A required document is missing",
                        "Whether every document required for this type of claim is present"),
                input -> Facts.findings(input, "MISSING_REQUIRED_DOCUMENT", null).stream()
                        .map(finding -> new Finding(
                                "No %s was provided.".formatted(Wording.document(finding.expected()).toLowerCase(Locale.ROOT)),
                                List.of(new Evidence("Required document", Wording.document(finding.expected()), null, null, null, null,
                                        null))))
                        .toList());
    }

    /** Conflicting values are consistency indicators instead, so they are not counted twice. */
    private static Rule criticalFacts() {
        return new Rule(
                new RuleDescription("MIS-02", 1, Category.MISSING_INFORMATION, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "Critical facts are not established",
                        "Whether the documents clearly state the patient, hospital, dates, amount, diagnosis, and policy number"),
                input -> {
                    Facts facts = new Facts(input);
                    List<String> missing = CRITICAL.stream()
                            .filter(field -> facts.claimFact(field.getKey())
                                    .map(fact -> "MISSING".equals(fact.state()) || "UNCERTAIN".equals(fact.state()))
                                    .orElse(true))
                            .map(Map.Entry::getValue)
                            .toList();
                    return missing.isEmpty()
                            ? List.of()
                            : List.of(new Finding(
                                    "The documents do not clearly state the %s.".formatted(String.join(", ", missing)),
                                    List.of(new Evidence("Claim facts", "Not established: " + String.join(", ", missing),
                                            null, null, null, null, null))));
                });
    }
}
