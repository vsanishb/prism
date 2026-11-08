package dev.prism.core.risk.internal;

import java.util.List;
import java.util.stream.Stream;

/** Every rule, in the order reviewers see them. Changing a rule's logic means raising its version. */
public final class Ruleset {

    public static final String VERSION = "risk-rules-1";

    private static final List<Rule> RULES = Stream.of(
                    ConsistencyRules.all(),
                    FinancialRules.all(),
                    DuplicateRules.all(),
                    DocumentRules.all(),
                    MissingInformationRules.all())
            .flatMap(List::stream)
            .toList();

    private Ruleset() {}

    public static List<Rule> rules() {
        return RULES;
    }
}
