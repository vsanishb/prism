package dev.prism.core.policyknowledge.internal;

import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.aiclient.PolicyExtraction.Reference;
import dev.prism.core.policyknowledge.internal.Assembly.Provision;
import dev.prism.core.policyknowledge.internal.Assembly.Relation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins page batches into one set of provisions, in plain code. Batches overlap by a page, so a
 * provision cut by a page break is seen whole by the next batch, and duplicates merge by key.
 */
final class PolicyAssembler {

    static final int BATCH_PAGES = 6;

    private static final Pattern CLAUSE_CITATION = Pattern.compile("(?i)\\bclauses?\\s+(\\d+(?:\\.\\d+)+)");
    private static final Pattern NUMERIC_KEY = Pattern.compile("\\d+(\\.\\d+)*");

    private PolicyAssembler() {}

    /** Ranges of at most six pages, each starting on the previous range's last page. */
    static List<int[]> batches(int pageCount) {
        List<int[]> ranges = new ArrayList<>();
        int first = 1;
        while (true) {
            int last = Math.min(first + BATCH_PAGES - 1, pageCount);
            ranges.add(new int[] {first, last});
            if (last == pageCount) {
                return ranges;
            }
            first = last;
        }
    }

    static List<Provision> provisions(List<PolicyExtraction.Result> batches) {
        Map<String, Provision> byIdentity = new LinkedHashMap<>();
        Provision cut = null;
        for (PolicyExtraction.Result batch : batches.stream().sorted(Comparator.comparingInt(PolicyExtraction.Result::firstPage)).toList()) {
            Provision lastOfBatch = null;
            for (PolicyExtraction.Provision source : batch.provisions()) {
                if (cut != null && source.continuedFromPreviousPage() && (source.key() == null || source.key().equals(cut.key))) {
                    join(cut, source);
                    lastOfBatch = cut;
                    continue;
                }
                String identity = identity(source);
                Provision existing = byIdentity.get(identity);
                if (existing == null) {
                    existing = new Provision(source);
                    byIdentity.put(identity, existing);
                } else {
                    mergeDuplicate(existing, source);
                }
                lastOfBatch = existing;
            }
            cut = lastOfBatch != null && lastOfBatch.continuesOnNextPage && lastOfBatch.pageEnd == batch.lastPage()
                    ? lastOfBatch
                    : null;
        }
        return byIdentity.values().stream().sorted(PolicyAssembler::documentOrder).toList();
    }

    /** A clause seen in two overlapping batches. The fuller reading wins, and nothing either found is lost. */
    private static void mergeDuplicate(Provision existing, PolicyExtraction.Provision other) {
        if (other.text().length() > existing.text.length()) {
            existing.text = other.text();
            existing.textVerified = other.textVerified();
        }
        existing.continuesOnNextPage = existing.continuesOnNextPage && other.continuesOnNextPage();
        existing.absorb(other);
    }

    /** The rest of a provision that a page break cut, read from the start of the next batch. */
    private static void join(Provision cut, PolicyExtraction.Provision rest) {
        if (!cut.text.contains(rest.text())) {
            cut.text = cut.text + " " + rest.text();
        }
        cut.textVerified = cut.textVerified && rest.textVerified();
        cut.continuesOnNextPage = rest.continuesOnNextPage();
        cut.absorb(rest);
    }

    private static String identity(PolicyExtraction.Provision provision) {
        if (provision.key() != null) {
            return "key:" + provision.key();
        }
        return "untitled:" + provision.type() + ":" + provision.title().toLowerCase(Locale.ROOT).strip();
    }

    private static int documentOrder(Provision first, Provision second) {
        int byPage = Integer.compare(first.pageStart, second.pageStart);
        if (byPage != 0 || first.key == null || second.key == null) {
            return byPage;
        }
        return compareKeys(first.key, second.key);
    }

    /** "2.10" comes after "2.9". */
    static int compareKeys(String first, String second) {
        if (!NUMERIC_KEY.matcher(first).matches() || !NUMERIC_KEY.matcher(second).matches()) {
            return first.compareTo(second);
        }
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int compared = Integer.compare(Integer.parseInt(left[index]), Integer.parseInt(right[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    /**
     * Relations from three sources: the model's citations, citations code finds in the text, and
     * defined terms used in other provisions. A model citation the text does not state is kept, marked.
     */
    static List<Relation> relations(List<Provision> provisions) {
        Map<String, Provision> byKey = new LinkedHashMap<>();
        provisions.stream().filter(provision -> provision.key != null).forEach(provision -> byKey.putIfAbsent(provision.key, provision));
        List<Relation> relations = new ArrayList<>();
        for (Provision provision : provisions) {
            Set<String> cited = new HashSet<>();
            for (Reference reference : provision.references) {
                if (reference.targetKey().equals(provision.key)) {
                    continue;
                }
                cited.add(reference.targetKey());
                relations.add(new Relation(
                        provision, reference.targetKey(), byKey.get(reference.targetKey()), reference.relation(), "MODEL",
                        reference.statedInText()));
            }
            Matcher citation = CLAUSE_CITATION.matcher(provision.text);
            while (citation.find()) {
                String target = citation.group(1);
                if (cited.add(target) && !target.equals(provision.key)) {
                    relations.add(new Relation(provision, target, byKey.get(target), "REFERENCES", "RULE", true));
                }
            }
        }
        relations.addAll(definitionUses(provisions));
        return relations;
    }

    private static List<Relation> definitionUses(List<Provision> provisions) {
        List<Relation> uses = new ArrayList<>();
        for (Provision definition : provisions) {
            if (!"DEFINITION".equals(definition.type) || definition.key == null || definition.title.isBlank()) {
                continue;
            }
            Pattern term = Pattern.compile("(?i)\\b" + Pattern.quote(definition.title.strip()) + "\\b");
            for (Provision user : provisions) {
                boolean alreadyLinked = user.references.stream().anyMatch(reference -> reference.targetKey().equals(definition.key));
                if (user != definition && !alreadyLinked && term.matcher(user.text).find()) {
                    uses.add(new Relation(user, definition.key, definition, "DEFINES", "RULE", true));
                }
            }
        }
        return uses;
    }
}
