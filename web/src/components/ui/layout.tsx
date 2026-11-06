import { CircleAlert, Lock } from "lucide-react";
import type { ReactNode } from "react";
import { EmptyState } from "./feedback";

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight">{title}</h1>
        {description ? <p className="mt-1 max-w-2xl text-ink-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </header>
  );
}

export function Panel({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-panel border border-line bg-surface ${className}`}>{children}</section>;
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return <h2 className="mb-3 text-[13px] font-medium text-ink-muted">{children}</h2>;
}

export function AccessDenied() {
  return (
    <Panel>
      <EmptyState icon={Lock} title="You do not have access to this page">
        Ask an administrator if you need it for your work.
      </EmptyState>
    </Panel>
  );
}

export function LoadFailed() {
  return (
    <Panel>
      <EmptyState icon={CircleAlert} title="This page could not be loaded">
        The core service may be unavailable. Reload to try again.
      </EmptyState>
    </Panel>
  );
}
