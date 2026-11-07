package dev.prism.core.intelligence.internal;

import static dev.prism.core.intelligence.internal.ConsolidatedClaim.CONFLICTING;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.MISSING;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.PRESENT;
import static dev.prism.core.intelligence.internal.ConsolidatedClaim.UNCERTAIN;

import dev.prism.core.aiclient.DocumentExtraction.Field;
import dev.prism.core.aiclient.DocumentExtraction.LineItem;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.DocumentLineItem;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.ExtractedDocument;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Fact;
import dev.prism.core.intelligence.internal.ConsolidatedClaim.Source;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Merges facts across documents, in plain code. Documents that disagree give a CONFLICTING fact with
 * every source kept: no value is ever chosen on a reviewer's behalf.
 */
final class FactConsolidator {

    /** Facts about the claim as a whole. Everything else belongs to its own document, like a bill total. */
    private static final Map<String, FieldShape> CLAIM_FIELDS = Map.of(
            "policy_number", new FieldShape("TEXT", true),
            "claimant_name", new FieldShape("TEXT", false),
            "patient_name", new FieldShape("TEXT", true),
            "hospital_name", new FieldShape("TEXT", true),
            "admission_date", new FieldShape("DATE", true),
            "discharge_date", new FieldShape("DATE", true),
            "claimed_amount", new FieldShape("AMOUNT", true),
            "diagnosis", new FieldShape("TEXT", true),
            "procedure", new FieldShape("TEXT", false));

    private static final Set<String> TITLES =
            Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "smt", "kumari", "master", "prof");

    private FactConsolidator() {}

    private record FieldShape(String kind, boolean critical) {}

    static List<Fact> facts(List<ExtractedDocument> documents) {
        List<Fact> facts = new ArrayList<>();
        CLAIM_FIELDS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> facts.add(claimFact(entry.getKey(), entry.getValue(), documents)));
        for (ExtractedDocument document : documents) {
            document.result().fields().stream()
                    .filter(field -> !CLAIM_FIELDS.containsKey(field.name()))
                    .forEach(field -> facts.add(documentFact(document, field)));
        }
        return facts;
    }

    static List<DocumentLineItem> lineItems(List<ExtractedDocument> documents) {
        List<DocumentLineItem> items = new ArrayList<>();
        for (ExtractedDocument document : documents) {
            int position = 0;
            for (LineItem item : document.result().lineItems()) {
                items.add(new DocumentLineItem(document.documentId(), ++position, item));
            }
        }
        return items;
    }

    private static Fact claimFact(String name, FieldShape shape, List<ExtractedDocument> documents) {
        List<Source> sources = documents.stream()
                .flatMap(document -> document.result().fields().stream()
                        .filter(field -> name.equals(field.name()) && !MISSING.equals(field.state()))
                        .map(field -> new Source(document.documentId(), field)))
                .toList();
        List<Source> present = sources.stream().filter(source -> PRESENT.equals(source.field().state())).toList();

        if (present.isEmpty()) {
            String state = sources.isEmpty() ? MISSING : UNCERTAIN;
            return new Fact(name, shape.kind(), shape.critical(), null, state, null, null, sources);
        }
        boolean agreed = present.stream().allMatch(source -> sameValue(shape.kind(), present.getFirst(), source));
        if (!agreed) {
            return new Fact(name, shape.kind(), shape.critical(), null, CONFLICTING, null, null, sources);
        }
        String value = fullest(present);
        return new Fact(name, shape.kind(), shape.critical(), null, PRESENT, value, amountOf(shape.kind(), value), sources);
    }

    private static Fact documentFact(ExtractedDocument document, Field field) {
        boolean stated = PRESENT.equals(field.state());
        List<Source> sources = MISSING.equals(field.state())
                ? List.of()
                : List.of(new Source(document.documentId(), field));
        return new Fact(
                field.name(),
                field.kind(),
                field.critical(),
                document.documentId(),
                field.state(),
                stated ? field.value() : null,
                stated ? amountOf(field.kind(), field.value()) : null,
                sources);
    }

    private static boolean sameValue(String kind, Source first, Source second) {
        return sameValue(kind, first.field().value(), second.field().value());
    }

    /** One reading may be fuller than another, such as a hospital's name with or without its city. */
    static boolean sameValue(String kind, String first, String second) {
        return switch (kind) {
            case "AMOUNT" -> new BigDecimal(first).compareTo(new BigDecimal(second)) == 0;
            case "DATE" -> first.equals(second);
            default -> {
                List<String> left = words(first);
                List<String> right = words(second);
                yield containsRun(left, right) || containsRun(right, left);
            }
        };
    }

    private static String fullest(List<Source> sources) {
        return sources.stream()
                .map(source -> source.field().value())
                .reduce((best, next) -> next.length() > best.length() ? next : best)
                .orElseThrow();
    }

    private static BigDecimal amountOf(String kind, String value) {
        return "AMOUNT".equals(kind) ? new BigDecimal(value) : null;
    }

    private static List<String> words(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return Stream.of(folded.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty() && !TITLES.contains(word))
                .toList();
    }

    private static boolean containsRun(List<String> text, List<String> run) {
        return !run.isEmpty() && Collections.indexOfSubList(text, run) >= 0;
    }
}
