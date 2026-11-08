"use client";

import { ArrowUpRight, Info } from "lucide-react";
import { useRef, useState } from "react";
import { Badge } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { formatMoney } from "@/lib/format";
import { DOCUMENT_TYPE_LABELS, FACT_NAME_LABELS, QUEUE_LABELS, TASK_REASON_LABELS } from "@/lib/labels";
import type { ClaimDetail, ClaimFact, ClaimFacts, PolicyAssessment, ReviewWorkspace, RiskScreening } from "@/lib/types";
import { SourcePane, type SourceTarget } from "./source-pane";
import { NotesPanel, TaskActions } from "./task-actions";
import { AnalysisPanel, FactsPanel, QuestionsPanel, RecordPanel, RiskPanel } from "./workspace-panels";

function TaskStatus({ review }: { review: ReviewWorkspace }) {
  const task = review.task;
  if (!task) {
    const decided = review.outcomes.some((outcome) => outcome.current);
    return (
      <p className="text-sm text-ink-muted">
        {decided ? "This claim is decided. Its outcome is below." : "No task is open for this claim."}
      </p>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="task-status">
      <Badge tone={task.queue === "INVESTIGATION" ? "critical" : task.queue === "SENIOR_REVIEW" ? "caution" : "accent"}>
        {QUEUE_LABELS[task.queue]}
      </Badge>
      <span>{TASK_REASON_LABELS[task.reason]}</span>
      <span className="text-ink-muted">
        · {task.status === "CLAIMED" ? (review.taskIsMine ? "Taken by you" : `Taken by ${task.assigneeName ?? "someone"}`) : "Waiting to be taken"}
      </span>
    </div>
  );
}

/**
 * The review workspace (plan: human review): one screen with the documents beside the facts, the
 * policy analysis, the risk screening, the open questions, and the record. Statements open their
 * source page in one step.
 */
export function ReviewWorkspaceView({
  claim,
  facts,
  assessment,
  screening,
  review,
}: {
  claim: ClaimDetail;
  facts: ClaimFacts | null;
  assessment: PolicyAssessment | null;
  screening: RiskScreening | null;
  review: ReviewWorkspace;
}) {
  const [source, setSource] = useState<SourceTarget | null>(null);
  const [correcting, setCorrecting] = useState<ClaimFact | null>(null);
  const pane = useRef<HTMLDivElement>(null);
  const claimId = claim.summary.id;
  const openRequest = review.requests.find((request) => request.status === "OPEN");
  const pending = review.corrections.filter((correction) => !correction.applied);

  function open(next: SourceTarget) {
    setSource(next);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      requestAnimationFrame(() => pane.current?.scrollIntoView({ block: "start" }));
    }
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <div className="min-w-0 space-y-4">
        <Panel className="space-y-3 p-5">
          <TaskStatus review={review} />
          {review.task?.escalationNote ? (
            <div className="flex gap-2 rounded-control bg-caution-soft px-3 py-2 text-sm" role="note">
              <ArrowUpRight aria-hidden className="mt-0.5 size-4 shrink-0 text-caution" />
              <p>
                <span className="font-medium">Escalated: </span>
                {review.task.escalationNote}
              </p>
            </div>
          ) : null}
          {openRequest ? (
            <div className="rounded-control bg-subtle px-3 py-2 text-sm" role="status">
              <p className="font-medium">
                Waiting for {openRequest.documentTypes.map((type) => DOCUMENT_TYPE_LABELS[type].toLowerCase()).join(", ")}
              </p>
              <p className="text-ink-muted">“{openRequest.message}” · {openRequest.requestedBy}</p>
            </div>
          ) : null}
          {review.createdByYou ? (
            <div className="flex gap-2 rounded-control bg-subtle px-3 py-2 text-sm text-ink-muted" role="note">
              <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
              You created this claim, so someone else must review and decide it.
            </div>
          ) : null}
          <TaskActions
            claimId={claimId}
            review={review}
            claimedAmount={claim.summary.claimedAmount}
            currency={claim.summary.currency}
            correcting={correcting}
            onCorrectDone={() => setCorrecting(null)}
          />
          <p className="text-xs text-ink-muted">
            Reviewer authority limit {formatMoney(review.authorityLimit, claim.summary.currency)}. Above it, claims go to senior review.
          </p>
        </Panel>

        <FactsPanel
          facts={facts}
          currency={claim.summary.currency}
          selected={source?.key ?? null}
          onOpen={open}
          onCorrect={review.actions.includes("CORRECT_FACT") ? setCorrecting : null}
        />
        {pending.length > 0 ? (
          <Panel className="p-5" >
            <h2 className="text-sm font-medium">Corrections not yet analyzed</h2>
            <ul className="mt-2 space-y-1.5 text-sm" data-testid="pending-corrections">
              {pending.map((correction) => (
                <li key={correction.id}>
                  {FACT_NAME_LABELS[correction.fieldName] ?? correction.fieldName}: {correction.originalValue ?? "not established"} →{" "}
                  <span className="font-medium">{correction.value}</span>
                  <span className="text-ink-muted"> · {correction.reason} · {correction.correctedBy}</span>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-ink-muted">They apply when the claim is analyzed again. That is your choice: nothing reruns by itself.</p>
          </Panel>
        ) : null}
        <AnalysisPanel claimId={claimId} assessment={assessment} />
        <RiskPanel claimId={claimId} screening={screening} selected={source?.key ?? null} onOpen={open} />
        <QuestionsPanel facts={facts} assessment={assessment} />
        <RecordPanel review={review} />
        <NotesPanel claimId={claimId} review={review} />
      </div>
      <div ref={pane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
        <SourcePane documents={facts?.documents ?? []} source={source} onOpen={open} />
      </div>
    </div>
  );
}
