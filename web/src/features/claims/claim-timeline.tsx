import { formatDateTime } from "@/lib/format";
import { actionLabel } from "@/lib/labels";
import type { TimelineEntry } from "@/lib/types";

export function ClaimTimeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-ink-muted">No activity yet.</p>;
  }
  return (
    <ol className="space-y-4 border-l border-line pl-4">
      {entries.map((entry, index) => (
        <li key={`${entry.occurredAt}-${index}`} className="relative">
          <span
            aria-hidden
            className={`absolute top-1.5 -left-[20.5px] size-2 rounded-full ${entry.outcome === "SUCCESS" ? "bg-line-strong" : "bg-critical"}`}
          />
          <p className="text-sm font-medium">{actionLabel(entry.action)}</p>
          <p className="text-xs text-ink-muted">
            {entry.actorName ?? "System"} · {formatDateTime(entry.occurredAt)}
          </p>
        </li>
      ))}
    </ol>
  );
}
