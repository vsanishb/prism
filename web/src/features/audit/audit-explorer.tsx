"use client";

import { ScrollText } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Dialog } from "@/components/ui/dialog";
import { Badge, EmptyState } from "@/components/ui/feedback";
import { Select } from "@/components/ui/field";
import { Panel } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { formatDateTime } from "@/lib/format";
import { actionLabel, AUDIT_ACTIONS, ROLE_LABELS } from "@/lib/labels";
import type { AuditEvent, AuditOutcome, Page } from "@/lib/types";

const OUTCOME_TONES = { SUCCESS: "positive", DENIED: "caution", FAILURE: "critical" } as const;
const OUTCOME_LABELS: Record<AuditOutcome, string> = { SUCCESS: "Success", DENIED: "Denied", FAILURE: "Failure" };

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-[120px_1fr] gap-3 py-1.5 text-[13px]">
      <dt className="text-ink-muted">{label}</dt>
      <dd className="min-w-0 break-words">{value}</dd>
    </div>
  );
}

export function AuditExplorer({
  page,
  action,
  outcome,
  paged,
}: {
  page: Page<AuditEvent>;
  action: string;
  outcome: string;
  paged: boolean;
}) {
  const router = useRouter();
  const [selected, setSelected] = useState<AuditEvent | null>(null);

  function search(overrides: { action?: string; outcome?: string; cursor?: string }) {
    const params = new URLSearchParams();
    const next = { action, outcome, ...overrides };
    if (next.action) params.set("action", next.action);
    if (next.outcome) params.set("outcome", next.outcome);
    if (next.cursor) params.set("cursor", next.cursor);
    return `/audit?${params}`;
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap gap-3">
        <div className="w-52">
          <Select aria-label="Filter by action" value={action} onChange={(event) => router.push(search({ action: event.target.value }))}>
            <option value="">All actions</option>
            {AUDIT_ACTIONS.map((value) => (
              <option key={value} value={value}>
                {actionLabel(value)}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-40">
          <Select aria-label="Filter by outcome" value={outcome} onChange={(event) => router.push(search({ outcome: event.target.value }))}>
            <option value="">All outcomes</option>
            {Object.entries(OUTCOME_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
        </div>
      </div>

      <Panel>
        {page.items.length === 0 ? (
          <EmptyState icon={ScrollText} title="No events match these filters" />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Time</Th>
                <Th>Action</Th>
                <Th>Actor</Th>
                <Th>Outcome</Th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((event) => (
                <tr key={event.id} className="cursor-pointer transition-colors hover:bg-canvas" onClick={() => setSelected(event)}>
                  <Td className="whitespace-nowrap text-ink-muted">{formatDateTime(event.occurredAt)}</Td>
                  <Td>
                    <button type="button" className="text-left font-medium whitespace-nowrap hover:text-accent" onClick={() => setSelected(event)}>
                      {actionLabel(event.action)}
                    </button>
                  </Td>
                  <Td className="whitespace-nowrap text-ink-muted">{event.actorName ?? "Unknown or system"}</Td>
                  <Td>
                    <Badge tone={OUTCOME_TONES[event.outcome]}>{OUTCOME_LABELS[event.outcome]}</Badge>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>

      <div className="mt-4 flex gap-4 text-[13px] font-medium">
        {paged ? (
          <Link href={search({})} className="text-accent">
            Back to latest
          </Link>
        ) : null}
        {page.nextCursor ? (
          <Link href={search({ cursor: page.nextCursor })} className="text-accent">
            Older events
          </Link>
        ) : null}
      </div>

      <Dialog open={selected !== null} onOpenChange={(open) => !open && setSelected(null)} title={selected ? actionLabel(selected.action) : ""}>
        {selected ? (
          <dl className="divide-y divide-line">
            <DetailRow label="Time" value={formatDateTime(selected.occurredAt)} />
            <DetailRow label="Outcome" value={OUTCOME_LABELS[selected.outcome]} />
            <DetailRow label="Actor" value={selected.actorName ?? "Unknown or system"} />
            <DetailRow label="Actor roles" value={selected.actorRoles.map((role) => ROLE_LABELS[role]).join(", ") || "None"} />
            {selected.targetType ? <DetailRow label="Target" value={`${selected.targetType} ${selected.targetId ?? ""}`} /> : null}
            <DetailRow label="Chain position" value={`${selected.stream} #${selected.sequence}`} />
            {selected.correlationId ? <DetailRow label="Correlation" value={selected.correlationId} /> : null}
            {Object.entries(selected.details).map(([key, value]) => (
              <DetailRow key={key} label={key.replaceAll("_", " ").toLowerCase()} value={value} />
            ))}
          </dl>
        ) : null}
      </Dialog>
    </>
  );
}
