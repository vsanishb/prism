"use client";

import { Inbox, Play } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge, EmptyState, Notice } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { ApiError, apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime, formatMoney } from "@/lib/format";
import { QUEUE_DESCRIPTIONS, QUEUE_LABELS, RISK_BAND_LABELS, TASK_REASON_LABELS } from "@/lib/labels";
import type { TaskLists, TaskRow } from "@/lib/types";
import { BAND_TONES } from "@/features/risk/risk-format";

function TaskTable({ rows, onTake, busy }: { rows: TaskRow[]; onTake?: (row: TaskRow) => void; busy: string | null }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <thead>
          <tr>
            <Th>Claim</Th>
            <Th>Queue</Th>
            <Th>Why</Th>
            <Th>Risk</Th>
            <Th className="text-right">Claimed</Th>
            <Th>{onTake ? "Waiting since" : "Taken"}</Th>
            <Th />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} data-testid="task-row">
              <Td>
                <Link href={`/claims/${row.claimId}/review`} className="font-medium whitespace-nowrap hover:text-accent">
                  {row.claimNumber}
                </Link>
                <p className="text-xs text-ink-muted">{row.patientName}</p>
              </Td>
              <Td className="whitespace-nowrap">{QUEUE_LABELS[row.queue]}</Td>
              <Td className="min-w-40 text-ink-muted">{TASK_REASON_LABELS[row.reason]}</Td>
              <Td>{row.riskBand ? <Badge tone={BAND_TONES[row.riskBand]}>{RISK_BAND_LABELS[row.riskBand]}</Badge> : "Not screened"}</Td>
              <Td className="text-right whitespace-nowrap tabular-nums">{formatMoney(row.claimedAmount, row.currency)}</Td>
              <Td className="whitespace-nowrap text-ink-muted">{formatDateTime(onTake ? row.createdAt : row.claimedAt)}</Td>
              <Td className="text-right">
                {onTake ? (
                  <Button variant="secondary" loading={busy === row.id} onClick={() => onTake(row)}>
                    Take
                  </Button>
                ) : (
                  <Link href={`/claims/${row.claimId}/review`} className="text-[13px] font-medium text-accent hover:text-accent-hover">
                    Open
                  </Link>
                )}
              </Td>
            </tr>
          ))}
        </tbody>
      </Table>
    </div>
  );
}

/** The queues this user may work, their own tasks, and the tasks waiting. Taking a task opens its workspace. */
export function ReviewDashboard({ lists }: { lists: TaskLists }) {
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function take(path: string, key: string) {
    setBusy(key);
    setMessage(null);
    try {
      const { claimId } = await apiRequest<{ claimId: string }>("POST", path);
      router.push(`/claims/${claimId}/review`);
    } catch (failure) {
      setMessage(failure instanceof ApiError && failure.status === 404 ? "No task is waiting in your queues." : errorMessage(failure));
      setBusy(null);
      router.refresh();
    }
  }

  return (
    <div className="space-y-8">
      <div className="grid gap-3 sm:grid-cols-3">
        {lists.queues.map((queue) => (
          <Panel key={queue.queue} className="p-4">
            <p className="text-[13px] font-medium">{QUEUE_LABELS[queue.queue]}</p>
            <p className="mt-1 text-[22px] font-semibold tabular-nums" data-testid={`queue-${queue.queue}`}>
              {queue.open}
            </p>
            <p className="text-xs text-ink-muted">waiting · {QUEUE_DESCRIPTIONS[queue.queue]}</p>
          </Panel>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button loading={busy === "next"} onClick={() => take("/api/v1/review/tasks/next", "next")}>
          <Play aria-hidden className="size-4" />
          Take next task
        </Button>
        <p className="text-xs text-ink-muted">The oldest task in your queues. Claims you created are skipped: someone else reviews them.</p>
      </div>
      {message ? <Notice tone="critical" title={message} /> : null}

      <section>
        <SectionTitle>Your tasks</SectionTitle>
        <Panel>
          {lists.mine.length === 0 ? (
            <EmptyState icon={Inbox} title="No tasks in progress">
              Take a task to start reviewing.
            </EmptyState>
          ) : (
            <TaskTable rows={lists.mine} busy={busy} />
          )}
        </Panel>
      </section>

      <section>
        <SectionTitle>Waiting</SectionTitle>
        <Panel>
          {lists.available.length === 0 ? (
            <EmptyState icon={Inbox} title="Nothing waiting">
              Claims appear here once processing finishes.
            </EmptyState>
          ) : (
            <TaskTable rows={lists.available} busy={busy} onTake={(row) => take(`/api/v1/review/tasks/${row.id}/claim`, row.id)} />
          )}
        </Panel>
      </section>
    </div>
  );
}
