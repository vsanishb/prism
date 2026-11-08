package dev.prism.core.risk.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Comparison keys. Matching is on these, never on raw text, so spacing, case, and titles do not matter. */
public final class Normalized {

    private static final Set<String> TITLES =
            Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "smt", "kumari", "master", "prof");

    private Normalized() {}

    /** A person's or hospital's name: lower case, punctuation and honorifics dropped. */
    public static String name(String value) {
        if (value == null) {
            return "";
        }
        String plain = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ");
        return Arrays.stream(plain.trim().split("\\s+"))
                .filter(word -> !word.isEmpty() && !TITLES.contains(word))
                .collect(Collectors.joining(" "));
    }

    /**
     * An invoice number with separators removed. The same expression runs in SQL when loading history:
     * {@code upper(regexp_replace(value, '[^[:alnum:]]', '', 'g'))}.
     */
    public static String invoice(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{Alnum}]", "").toUpperCase(Locale.ROOT);
    }

    /** A line item's description. */
    public static String description(String value) {
        return name(value);
    }
}
