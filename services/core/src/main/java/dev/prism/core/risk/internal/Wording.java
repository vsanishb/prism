package dev.prism.core.risk.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Plain-language pieces shared by rules. Explanations carry no names, so that they can be summarized safely. */
final class Wording {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "CLAIM_FORM", "Claim form",
            "HOSPITAL_BILL", "Hospital bill",
            "ITEMIZED_INVOICE", "Itemized invoice",
            "DISCHARGE_SUMMARY", "Discharge summary",
            "PRESCRIPTION", "Prescription",
            "DIAGNOSTIC_REPORT", "Diagnostic report",
            "OTHER", "Other document");

    private Wording() {}

    static String document(String type) {
        return DOCUMENT_TYPES.getOrDefault(type, "Document");
    }

    static String page(String type, Integer page) {
        return page == null ? document(type) : document(type) + ", page " + page;
    }

    static String date(LocalDate date) {
        return DATE.format(date);
    }

    /** Indian digit grouping, as on the documents: 2,40,000.00. */
    static String money(BigDecimal amount, String currency) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        String digits = rounded.abs().toPlainString();
        String whole = digits.substring(0, digits.indexOf('.'));
        String fraction = digits.substring(digits.indexOf('.'));
        StringBuilder grouped = new StringBuilder();
        int length = whole.length();
        for (int index = 0; index < length; index++) {
            grouped.append(whole.charAt(index));
            int remaining = length - index - 1;
            if (remaining > 0 && (remaining == 3 || (remaining > 3 && (remaining - 3) % 2 == 0))) {
                grouped.append(',');
            }
        }
        return currency + " " + (rounded.signum() < 0 ? "-" : "") + grouped + fraction;
    }

    static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
