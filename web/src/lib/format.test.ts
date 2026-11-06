import { describe, expect, it } from "vitest";
import { formatBytes, formatDate, formatDateTime, formatMoney, initials } from "./format";

describe("formatDateTime", () => {
  it("shows Indian Standard Time", () => {
    expect(formatDateTime("2026-03-14T18:30:00Z")).toContain("15 Mar 2026");
  });

  it("describes a missing value", () => {
    expect(formatDateTime(null)).toBe("Never");
  });
});

describe("formatDate", () => {
  it("never shifts a calendar date", () => {
    expect(formatDate("2026-03-14")).toContain("14 Mar 2026");
  });
});

describe("formatMoney", () => {
  it("uses Indian digit grouping", () => {
    expect(formatMoney("106000.00", "INR")).toBe("₹1,06,000.00");
  });
});

describe("formatBytes", () => {
  it("uses readable units", () => {
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(3 * 1024 * 1024)).toBe("3.0 MB");
  });
});

describe("initials", () => {
  it("uses the first and last names", () => {
    expect(initials("Synthetic Senior Reviewer")).toBe("SR");
  });

  it("handles a single name", () => {
    expect(initials("Priya")).toBe("P");
  });
});
