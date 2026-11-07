import { describe, expect, it } from "vitest";
import { findingMessage } from "@/lib/labels";
import type { FactSource, Finding } from "@/lib/types";
import { distinctValues } from "./fact-review";

function source(value: string | null, state: FactSource["state"] = "PRESENT"): FactSource {
  return { documentId: "d", state, confidence: "HIGH", rawValue: value, value, page: 1, quote: "q", region: null, grounding: "TEXT_MATCH", reason: null };
}

function finding(overrides: Partial<Finding>): Finding {
  return { code: "", severity: "WARNING", fieldName: null, documentId: null, expectedValue: null, actualValue: null, ...overrides };
}

describe("distinctValues", () => {
  it("counts values, not the documents stating them", () => {
    expect(distinctValues([source("2026-03-14"), source("2026-03-15"), source("2026-03-14")])).toBe(2);
  });

  it("does not count an uncertain reading as a value", () => {
    expect(distinctValues([source("a"), source("b"), source(null, "UNCERTAIN")])).toBe(2);
  });
});

describe("findingMessage", () => {
  it("names both figures when charges do not add up", () => {
    const message = findingMessage(finding({ code: "LINE_ITEMS_DO_NOT_MATCH_TOTAL", documentId: "d", expectedValue: "106000.00", actualValue: "105000.00" }), [
      { id: "d", originalFilename: "bill.pdf", documentType: "HOSPITAL_BILL", classifiedType: null, pageCount: 1 },
    ]);
    expect(message).toBe("bill.pdf: the charges add up to ₹1,05,000.00, but the stated total is ₹1,06,000.00.");
  });

  it("says a conflict has not been resolved for the reader", () => {
    expect(findingMessage(finding({ code: "VALUES_CONFLICT", fieldName: "admission_date" }), [])).toBe(
      "Admission date: documents state different values. None has been chosen.",
    );
  });

  it("never uses the language of accusation or decision", () => {
    const codes = ["VALUES_CONFLICT", "VALUE_UNCERTAIN", "VALUE_MISSING", "DISCHARGE_BEFORE_ADMISSION", "CLAIMED_AMOUNT_DIFFERS_FROM_BILLS"];
    for (const code of codes) {
      expect(findingMessage(finding({ code, fieldName: "total_amount" }), []).toLowerCase()).not.toMatch(/fraud|suspicious|reject|denied|false/);
    }
  });
});
