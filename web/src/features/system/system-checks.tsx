"use client";

import { CircleCheck, CircleX, RefreshCw } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { formatDateTime } from "@/lib/format";
import type { SystemStatus } from "@/lib/types";

export function SystemChecks({ status }: { status: SystemStatus }) {
  const router = useRouter();
  const [refreshing, startRefresh] = useTransition();
  const failing = status.checks.filter((check) => !check.healthy).length;

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Badge tone={failing === 0 ? "positive" : "critical"}>
            {failing === 0 ? "All checks passing" : `${failing} ${failing === 1 ? "check" : "checks"} failing`}
          </Badge>
          <p className="text-sm text-ink-muted">Checked {formatDateTime(status.checkedAt)}</p>
        </div>
        <Button variant="secondary" loading={refreshing} onClick={() => startRefresh(() => router.refresh())}>
          {refreshing ? null : <RefreshCw aria-hidden className="size-3.5" />}
          Run checks
        </Button>
      </div>
      <Panel className="divide-y divide-line">
        {status.checks.map((check) => (
          <div key={check.id} className="flex items-center gap-3 px-5 py-4">
            {check.healthy ? (
              <CircleCheck aria-label="Passing" className="size-5 shrink-0 text-positive" />
            ) : (
              <CircleX aria-label="Failing" className="size-5 shrink-0 text-critical" />
            )}
            <div>
              <p className="font-medium">{check.name}</p>
              <p className="text-sm text-ink-muted">{check.detail}</p>
            </div>
          </div>
        ))}
      </Panel>
    </>
  );
}
