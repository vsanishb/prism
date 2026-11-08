package dev.prism.core.risk;

import dev.prism.core.risk.internal.Rule;
import dev.prism.core.risk.internal.Ruleset;
import java.util.List;

/**
 * Runs every rule against one claim. Deterministic: rules read only their input, which core loads
 * from its own records, so the same input always gives the same indicators, in the same order.
 */
public final class RuleEngine {

    public static final String RULESET_VERSION = Ruleset.VERSION;

    private RuleEngine() {}

    public static List<Indicator> evaluate(RiskInput input) {
        return Ruleset.rules().stream().flatMap(rule -> rule.evaluate(input).stream()).toList();
    }

    public static List<RuleDescription> rules() {
        return Ruleset.rules().stream().map(Rule::description).toList();
    }
}
