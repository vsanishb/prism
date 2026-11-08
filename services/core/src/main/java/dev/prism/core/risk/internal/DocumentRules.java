package dev.prism.core.risk.internal;

import static dev.prism.core.risk.internal.Facts.BILLS;

import dev.prism.core.risk.Category;
import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.IndicatorKind;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RuleDescription;
import dev.prism.core.risk.Severity;
import dev.prism.core.risk.internal.Rule.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Documents that are not what they say they are, or lack what such a document normally has. */
final class DocumentRules {

    private static final List<Map.Entry<String, String>> BILL_FIELDS = List.of(
            Map.entry("bill_number", "bill number"), Map.entry("bill_date", "bill date"), Map.entry("total_amount", "total"));

    private DocumentRules() {}

    static List<Rule> all() {
        return List.of(typeDiffers(), billMissingFields(), billWithoutItems());
    }

    private static Rule typeDiffers() {
        return new Rule(
                new RuleDescription("DOC-01", 1, Category.DOCUMENT, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "A document reads as a different type",
                        "Whether each document reads as the type it was uploaded as"),
                input -> Facts.findings(input, "DOCUMENT_TYPE_DIFFERS", null).stream()
                        .filter(finding -> finding.documentId() != null)
                        .map(finding -> new Finding(
                                "A document uploaded as a %s reads as a %s.".formatted(
                                        Wording.document(finding.expected()).toLowerCase(Locale.ROOT),
                                        Wording.document(finding.actual()).toLowerCase(Locale.ROOT)),
                                List.of(Evidence.onPage(Wording.document(finding.actual()), null, finding.documentId(), null))))
                        .toList());
    }

    private static Rule billMissingFields() {
        return new Rule(
                new RuleDescription("DOC-02", 1, Category.DOCUMENT, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "A bill is missing expected details",
                        "Whether each bill states a bill number, a date, and a total"),
                input -> {
                    Facts facts = new Facts(input);
                    List<Finding> found = new ArrayList<>();
                    for (RiskInput.Document bill : facts.documentsOf(BILLS)) {
                        List<String> missing = BILL_FIELDS.stream()
                                .filter(field -> facts.documentFact(bill.id(), field.getKey())
                                        .map(fact -> "MISSING".equals(fact.state()))
                                        .orElse(true))
                                .map(Map.Entry::getValue)
                                .toList();
                        if (!missing.isEmpty()) {
                            found.add(new Finding(
                                    "%s does not state its %s.".formatted(Wording.document(bill.type()), String.join(" or ", missing)),
                                    List.of(Evidence.onPage(Wording.document(bill.type()), "Not found: " + String.join(", ", missing),
                                            bill.id(), null))));
                        }
                    }
                    return found;
                });
    }

    private static Rule billWithoutItems() {
        return new Rule(
                new RuleDescription("DOC-03", 1, Category.DOCUMENT, IndicatorKind.RISK_INDICATOR, Severity.LOW,
                        "A bill has no line items",
                        "Whether each bill lists its charges"),
                input -> {
                    Facts facts = new Facts(input);
                    return facts.documentsOf(BILLS).stream()
                            .filter(bill -> facts.items(bill.id()).isEmpty())
                            .map(bill -> new Finding(
                                    "%s lists no charges, only a total.".formatted(Wording.document(bill.type())),
                                    List.of(Evidence.onPage(Wording.document(bill.type()), null, bill.id(), null))))
                            .toList();
                });
    }
}
