import { describe, expect, it } from "vitest";
import { formatDateTime, initials } from "./format";

describe("formatDateTime", () => {
  it("shows Indian Standard Time", () => {
    expect(formatDateTime("2026-03-14T18:30:00Z")).toContain("15 Mar 2026");
  });

  it("describes a missing value", () => {
    expect(formatDateTime(null)).toBe("Never");
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
