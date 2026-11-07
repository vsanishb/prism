package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.PolicyExtraction.Value;
import dev.prism.core.policyknowledge.internal.Assembly.Finding;
import dev.prism.core.policyknowledge.internal.Assembly.Provision;
import dev.prism.core.policyknowledge.internal.Assembly.Relation;
import dev.prism.core.policyknowledge.internal.Assembly.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deterministic checks on assembled policy knowledge. Findings point reviewers at what to confirm. */
final class PolicyValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern MINOR_KEY = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final Pattern CARVE_OUT = Pattern.compile("(?i)\\b(except|unless|does not apply|other than)\\b");

    private PolicyValidator() {}

    static List<Finding> validate(
            List<Provision> provisions, List<Relation> relations, int pageCount, List<int[]> readRanges, BigDecimal sumInsured) {
        List<Finding> findings = new ArrayList<>();
        coverage(pageCount, readRanges, findings);
        numbering(provisions, findings);
        for (Provision provision : provisions) {
            values(provision, sumInsured, findings);
            if (!provision.textVerified) {
                findings.add(Finding.of("PROVISION_TEXT_UNVERIFIED", Severity.WARNING, provision));
            }
            if (List.of("EXCLUSION", "WAITING_PERIOD").contains(provision.type)
                    && CARVE_OUT.matcher(provision.text).find()
                    && provision.exceptions.isEmpty()
                    && relations.stream().noneMatch(relation -> relation.from() == provision && "EXCEPTS".equals(relation.relation()))) {
                findings.add(Finding.of("EXCEPTION_NOT_CAPTURED", Severity.WARNING, provision));
            }
            boolean hasNumber = provision.values.stream().anyMatch(PolicyValidator::isCritical);
            if ("WAITING_PERIOD".equals(provision.type)
                    && provision.values.stream().noneMatch(value -> "DURATION".equals(value.kind()))) {
                findings.add(Finding.of("WAITING_PERIOD_WITHOUT_DURATION", Severity.WARNING, provision));
            }
            if ("BENEFIT".equals(provision.type) && provision.values.isEmpty() && !hasNumber) {
                findings.add(Finding.of("BENEFIT_WITHOUT_LIMIT", Severity.WARNING, provision));
            }
        }
        for (Relation relation : relations) {
            if (relation.to() == null) {
                findings.add(new Finding(
                        "UNRESOLVED_REFERENCE", Severity.WARNING, relation.from(), null, relation.targetKey(), null));
            } else if (!relation.statedInText()) {
                findings.add(new Finding(
                        "REFERENCE_NOT_IN_TEXT", Severity.INFO, relation.from(), null, relation.targetKey(), null));
            }
        }
        return findings;
    }

    static boolean isCritical(Value value) {
        return !"TEXT".equals(value.kind());
    }

    /**
     * A rule a value's figure must satisfy, checked again at publication against any correction. The
     * same function serves both, so that a correction is held to the same standard as a reading.
     */
    static Optional<Rule> violation(String provisionType, String provisionTitle, String valueName, String kind,
            BigDecimal number, BigDecimal sumInsured) {
        if (number == null) {
            return Optional.empty();
        }
        if ("PERCENTAGE".equals(kind) && number.compareTo(HUNDRED) > 0) {
            return Optional.of(new Rule("PERCENTAGE_OVER_100", HUNDRED));
        }
        if (!"AMOUNT".equals(kind)) {
            return Optional.empty();
        }
        if (isSumInsured(provisionType, provisionTitle, valueName)) {
            return number.compareTo(sumInsured) != 0
                    ? Optional.of(new Rule("SUM_INSURED_DIFFERS_FROM_REGISTER", sumInsured))
                    : Optional.empty();
        }
        return number.compareTo(sumInsured) > 0
                ? Optional.of(new Rule("LIMIT_EXCEEDS_SUM_INSURED", sumInsured))
                : Optional.empty();
    }

    record Rule(String code, BigDecimal reference) {}

    private static boolean isSumInsured(String provisionType, String provisionTitle, String valueName) {
        String label = (provisionTitle + " " + valueName).toLowerCase(Locale.ROOT);
        return "SCHEDULE".equals(provisionType) && label.contains("sum insured");
    }

    private static void values(Provision provision, BigDecimal sumInsured, List<Finding> findings) {
        for (Value value : provision.values) {
            if (!isCritical(value)) {
                continue;
            }
            if (!"PRESENT".equals(value.state())) {
                findings.add(new Finding("VALUE_UNCERTAIN", Severity.WARNING, provision, value, null, value.rawValue()));
                continue;
            }
            violation(provision.type, provision.title, value.name(), value.kind(), new BigDecimal(value.value()), sumInsured)
                    .ifPresent(rule -> findings.add(new Finding(
                            rule.code(), Severity.ERROR, provision, value, rule.reference().toPlainString(), value.value())));
        }
    }

    private static void coverage(int pageCount, List<int[]> readRanges, List<Finding> findings) {
        TreeSet<Integer> unread = new TreeSet<>();
        for (int page = 1; page <= pageCount; page++) {
            unread.add(page);
        }
        readRanges.forEach(range -> {
            for (int page = range[0]; page <= range[1]; page++) {
                unread.remove(page);
            }
        });
        if (!unread.isEmpty()) {
            findings.add(new Finding("PAGES_NOT_READ", Severity.ERROR, null, null, null, unread.toString()));
        }
    }

    /** Clauses 4.1, 4.2, 4.4 suggest a missed 4.3. A gap may be deliberate, so it is a warning. */
    private static void numbering(List<Provision> provisions, List<Finding> findings) {
        Map<Integer, TreeSet<Integer>> sections = new TreeMap<>();
        Map<String, Provision> byKey = new TreeMap<>();
        for (Provision provision : provisions) {
            if (provision.key == null) {
                continue;
            }
            var match = MINOR_KEY.matcher(provision.key);
            if (match.matches()) {
                sections.computeIfAbsent(Integer.parseInt(match.group(1)), section -> new TreeSet<>())
                        .add(Integer.parseInt(match.group(2)));
                byKey.put(provision.key, provision);
            }
        }
        sections.forEach((section, minors) -> {
            int expected = 1;
            for (int minor : minors) {
                for (int missing = expected; missing < minor; missing++) {
                    Provision after = byKey.get(section + "." + minor);
                    findings.add(new Finding(
                            "NUMBERING_GAP", Severity.WARNING, after, null, section + "." + missing, null));
                }
                expected = minor + 1;
            }
        });
    }
}
