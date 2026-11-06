const dateTime = new Intl.DateTimeFormat("en-IN", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Kolkata",
});

export function formatDateTime(value: string | null): string {
  return value ? dateTime.format(new Date(value)) : "Never";
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  const first = parts[0]?.[0] ?? "";
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "";
  return (first + last).toUpperCase();
}

const calendarDate = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeZone: "UTC" });

/** Formats a date without a time, such as an admission date, with no timezone shift. */
export function formatDate(value: string): string {
  return calendarDate.format(new Date(`${value}T00:00:00Z`));
}

export function formatMoney(amount: string, currency: string): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(Number(amount));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
