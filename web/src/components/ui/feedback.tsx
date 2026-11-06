import { CircleAlert, CircleCheck, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

type Tone = "neutral" | "positive" | "caution" | "critical" | "accent";

const BADGE_TONES: Record<Tone, string> = {
  neutral: "bg-subtle text-ink-muted",
  positive: "bg-positive-soft text-positive",
  caution: "bg-caution-soft text-caution",
  critical: "bg-critical-soft text-critical",
  accent: "bg-accent-soft text-accent",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${BADGE_TONES[tone]}`}>
      {children}
    </span>
  );
}

export function Notice({ tone, title, children }: { tone: "positive" | "critical"; title: string; children?: ReactNode }) {
  const Icon = tone === "positive" ? CircleCheck : CircleAlert;
  const color = tone === "positive" ? "bg-positive-soft text-positive" : "bg-critical-soft text-critical";
  return (
    <div role={tone === "critical" ? "alert" : "status"} className={`flex gap-2.5 rounded-control px-3 py-2.5 text-[13px] ${color}`}>
      <Icon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <div>
        <p className="font-medium">{title}</p>
        {children ? <div className="mt-0.5 text-ink-muted">{children}</div> : null}
      </div>
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  children,
}: {
  icon: LucideIcon;
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <span className="mb-3 flex size-10 items-center justify-center rounded-full bg-subtle text-ink-muted">
        <Icon aria-hidden className="size-5" />
      </span>
      <p className="font-medium">{title}</p>
      {children ? <div className="mt-1 max-w-sm text-[13px] text-ink-muted">{children}</div> : null}
    </div>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden className={`animate-pulse rounded-control bg-subtle ${className}`} />;
}
