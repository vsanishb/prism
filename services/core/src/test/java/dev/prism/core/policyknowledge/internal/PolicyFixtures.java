package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.aiclient.PolicyExtraction.Reference;
import dev.prism.core.aiclient.PolicyExtraction.Value;
import java.math.BigDecimal;
import java.util.List;

/** Policy page results as the AI service would return them. */
public final class PolicyFixtures {

    private static final List<BigDecimal> REGION =
            List.of(new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.3"), new BigDecimal("0.25"));

    private PolicyFixtures() {}

    public static Value value(String name, String kind, String value, String unit) {
        return new Value(name, kind, value, value, unit, null, "PRESENT", 1, name + " " + value, REGION, "TEXT_MATCH", null);
    }

    public static Value amount(String name, String value) {
        return value(name, "AMOUNT", value, "INR");
    }

    public static Value uncertain(String name, String kind) {
        return new Value(name, kind, "smudged", null, null, null, "UNCERTAIN", 1, "smudged", null, "NONE", "QUOTE_NOT_FOUND");
    }

    public static Reference reference(String target, String relation) {
        return new Reference(target, relation, true);
    }

    public static PolicyExtraction.Provision provision(
            String key, String type, String title, String text, int page, List<Value> values, List<Reference> references) {
        return new PolicyExtraction.Provision(
                key, type, title, text, page, text, REGION, "TEXT_MATCH", true, null, List.of(), references, values, false,
                false);
    }

    public static PolicyExtraction.Provision provision(String key, String type, String text, int page, List<Value> values) {
        return provision(key, type, key == null ? "" : key, text, page, values, List.of());
    }

    public static PolicyExtraction.Provision withExceptions(PolicyExtraction.Provision source, List<String> exceptions) {
        return new PolicyExtraction.Provision(
                source.key(), source.type(), source.title(), source.text(), source.page(), source.quote(), source.region(),
                source.grounding(), source.textVerified(), source.parentKey(), exceptions, source.references(), source.values(),
                source.continuesOnNextPage(), source.continuedFromPreviousPage());
    }

    public static PolicyExtraction.Provision cut(PolicyExtraction.Provision source, boolean continues, boolean continued) {
        return new PolicyExtraction.Provision(
                source.key(), source.type(), source.title(), source.text(), source.page(), source.quote(), source.region(),
                source.grounding(), source.textVerified(), source.parentKey(), source.exceptions(), source.references(),
                source.values(), continues, continued);
    }

    public static PolicyExtraction.Result batch(int first, int last, int pageCount, PolicyExtraction.Provision... provisions) {
        return new PolicyExtraction.Result("policy-1", pageCount, first, last, List.of(provisions), List.of());
    }

    /** A small wording, consistent with a sum insured of 5,00,000. */
    public static PolicyExtraction.Result goldWording() {
        return batch(1, 2, 2,
                provision("", "SCHEDULE", "Sum Insured", "Sum Insured INR 5,00,000 per policy year", 1,
                        List.of(amount("Sum insured", "500000.00")), List.of()),
                provision("1.1", "DEFINITION", "Hospitalization",
                        "1.1 Hospitalization means admission for at least 24 consecutive hours", 1,
                        List.of(value("Minimum stay", "DURATION", "24", "HOURS")), List.of()),
                provision("2.1", "BENEFIT", "In-patient", "2.1 In-patient Hospitalization Up to Sum Insured Subject to Clause 1.1",
                        1, List.of(value("Limit", "TEXT", "Up to Sum Insured", null)), List.of(reference("1.1", "QUALIFIES"))),
                provision("2.6", "BENEFIT", "Cataract surgery", "2.6 Cataract surgery INR 40,000 per eye See Clause 3.2", 1,
                        List.of(amount("Limit", "40000.00")), List.of(reference("3.2", "QUALIFIES"))),
                provision("3.2", "WAITING_PERIOD", "Cataract", "3.2 A 24-month waiting period applies to cataract", 2,
                        List.of(value("Waiting period", "DURATION", "24", "MONTHS")), List.of()),
                withExceptions(
                        provision("4.1", "EXCLUSION", "Cosmetic", "4.1 Cosmetic treatment, except reconstruction after an accident",
                                2, List.of(), List.of()),
                        List.of("except reconstruction after an accident")));
    }
}
