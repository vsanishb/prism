import { formatMoney } from "@/lib/format";
import type { RiskBand } from "@/lib/types";

export const BAND_TONES: Record<RiskBand, "positive" | "caution" | "critical"> = {
  LOW: "positive",
  MEDIUM: "caution",
  HIGH: "critical",
};

/** Beyond these baseline percentiles a value is marked unusual. Descriptive only: not the detectors' reasoning. */
export const UNUSUAL_BELOW = 2.5;
export const UNUSUAL_ABOVE = 97.5;

export function isUnusual(percentile: number): boolean {
  return percentile < UNUSUAL_BELOW || percentile > UNUSUAL_ABOVE;
}

function percent(value: number, digits = 0): string {
  return `${(value * 100).toFixed(digits)}%`;
}

/** Shows a feature in its own unit, so that a reviewer reads "2 nights", not a raw number. */
export function formatFeature(name: string, value: number, currency: string): string {
  switch (name) {
    case "claimedAmount":
    case "amountPerDay":
      return formatMoney(String(Math.round(value)), currency);
    case "sumInsuredRatio":
    case "largestItemShare":
      return percent(value);
    case "billDeviation":
      return value === 0 ? "Same as the bills" : `${value > 0 ? "+" : ""}${percent(value, 1)}`;
    case "lengthOfStayDays":
      return `${value} ${value === 1 ? "night" : "nights"}`;
    case "daysSinceCoverStart":
      return `${Math.round(value)} days`;
    default:
      return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
}

/** "More unusual than 99.84% of baseline claims", in words a reviewer can use. */
export function percentileText(percentile: number): string {
  return `More unusual than ${percentile.toFixed(percentile >= 99 ? 2 : 1).replace(/\.?0+$/, "")}% of baseline claims`;
}
