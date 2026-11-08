package dev.prism.core.risk.internal;

import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.Indicator;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RuleDescription;
import java.util.List;
import java.util.function.Function;

/** One deterministic check. A rule reads only its input, so it is reproducible and testable alone. */
public record Rule(RuleDescription description, Function<RiskInput, List<Finding>> check) {

    /** What a rule found: its explanation for this claim, and the evidence. */
    public record Finding(String explanation, List<Evidence> evidence) {}

    public List<Indicator> evaluate(RiskInput input) {
        return check.apply(input).stream()
                .map(found -> new Indicator(
                        description.id(),
                        description.version(),
                        description.category(),
                        description.kind(),
                        description.severity(),
                        description.title(),
                        found.explanation(),
                        List.copyOf(found.evidence())))
                .toList();
    }
}
