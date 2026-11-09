package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.DocumentExtraction.Classification;
import dev.prism.core.aiclient.DocumentExtraction.Field;
import dev.prism.core.aiclient.DocumentExtraction.LineItem;
import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import dev.prism.core.aiclient.DocumentExtraction.Result;
import java.math.BigDecimal;
import java.util.List;

/** Extraction results as the AI service would return them. */
public final class ExtractionFixtures {

    private static final List<BigDecimal> REGION =
            List.of(new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.3"), new BigDecimal("0.25"));

    private ExtractionFixtures() {}

    public static Field present(String name, String kind, boolean critical, String value) {
        return new Field(
                name, kind, critical, "PRESENT", "HIGH", value, value, 1, name + ": " + value, REGION, "TEXT_MATCH", null);
    }

    public static Field text(String name, String value) {
        return present(name, "TEXT", true, value);
    }

    public static Field date(String name, String value) {
        return present(name, "DATE", true, value);
    }

    public static Field amount(String name, String value) {
        return present(name, "AMOUNT", true, value);
    }

    public static Field missing(String name, String kind, boolean critical) {
        return new Field(name, kind, critical, "MISSING", "HIGH", null, null, null, null, null, "NONE", null);
    }

    public static Field uncertain(String name, String kind, String reason) {
        return new Field(name, kind, true, "UNCERTAIN", "LOW", "smudged", null, 1, "smudged", null, "PAGE_ONLY", reason);
    }

    public static LineItem item(String description, String amount) {
        return new LineItem(
                description, "1", amount, new BigDecimal(amount), amount, "PRESENT", 1, description + " " + amount,
                REGION, "TEXT_MATCH");
    }

    public static LineItem unverifiedItem(String description) {
        return new LineItem(description, null, null, null, "unclear", "UNCERTAIN", 1, description, null, "NONE");
    }

    public static ModelRun run(String outcome) {
        return new ModelRun(
                "extraction", "anthropic/claude-sonnet-5.5", "Google", "extract.v1", 1, outcome, 100, 20,
                new BigDecimal("0.0012"), 900);
    }

    public static Result result(String type, List<Field> fields, List<LineItem> items) {
        return resultClassified(type, "HIGH", type, fields, items);
    }

    public static Result resultClassified(
            String type, String confidence, String declared, List<Field> fields, List<LineItem> items) {
        return new Result(
                "doc-1", 1, new Classification(type, confidence, declared), fields, items, List.of(run("SUCCEEDED")));
    }

    public static Result claimForm(String admission, String amount) {
        return result(
                "CLAIM_FORM",
                List.of(
                        text("patient_name", "Anita Desai"),
                        text("hospital_name", "Vrinda Care Hospital"),
                        date("admission_date", admission),
                        date("discharge_date", "2026-03-17"),
                        amount("claimed_amount", amount)),
                List.of());
    }

    public static Result bill(String total, List<LineItem> items) {
        return result(
                "HOSPITAL_BILL",
                List.of(
                        text("patient_name", "Anita Desai"),
                        text("hospital_name", "Vrinda Care Hospital, Pune"),
                        date("admission_date", "2026-03-14"),
                        date("discharge_date", "2026-03-17"),
                        amount("total_amount", total)),
                items);
    }

    public static Result discharge(String admission) {
        return result(
                "DISCHARGE_SUMMARY",
                List.of(
                        text("patient_name", "Anita Desai"),
                        date("admission_date", admission),
                        date("discharge_date", "2026-03-17"),
                        text("diagnosis", "Acute appendicitis")),
                List.of());
    }
}
