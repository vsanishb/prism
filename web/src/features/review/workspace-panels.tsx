"use client";

import { CircleHelp, FileText, PencilLine, Scale, ShieldAlert } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { Badge } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import {
  AI_AGREEMENT_LABELS,
  DECISION_LABELS,
  FACT_NAME_LABELS,
  FACT_STATE_LABELS,
  FINDING_LABELS,
  FINDING_STATUS_LABELS,
  INDICATOR_KIND_LABELS,
  QUEUE_LABELS,
  RISK_BAND_LABELS,
  TASK_COMPLETION_LABELS,
  TASK_REASON_LABELS,
} from "@/lib/labels";
import type { ClaimFact, ClaimFacts, PolicyAssessment, ReviewWorkspace, RiskScreening } from "@/lib/types";
import { BAND_TONES } from "@/features/risk/risk-format";
import type { SourceTarget } from "./source-pane";

function Section({ icon: Icon, title, link, children }: { icon: typeof Scale; title: string; link?: ReactNode; children: ReactNode }) {
  return (
    <Panel>
      <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <h2 className="flex items-center gap-1.5 text-[13px] font-medium">
          <Icon aria-hidden className="size-4 text-ink-muted" />
          {title}
        </h2>
        {link}
      </div>
      <div className="px-5 py-4">{children}</div>
    </Panel>
  );
}

function SourceButton({ target, selected, onOpen, children }: { target: SourceTarget; selected: boolean; onOpen: (target: SourceTarget) => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={() => onOpen(target)}
      aria-pressed={selected}
      className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-medium hover:bg-accent-soft ${selected ? "bg-accent-soft text-accent" : "text-accent"}`}
    >
      <FileText aria-hidden className="size-3.5" />
      {children}
    </button>
  );
}

function displayValue(fact: ClaimFact, currency: string): string | null {
  if (fact.value === null) {
    return null;
  }
  if (fact.kind === "DATE") {
    return formatDate(fact.value);
  }
  return fact.kind === "AMOUNT" ? formatMoney(fact.value, currency) : fact.value;
}

/** Claim-level facts, each with its source one click away. Corrections are shown as such, never silently. */
export function FactsPanel({
  facts,
  currency,
  selected,
  onOpen,
  onCorrect,
}: {
  facts: ClaimFacts | null;
  currency: string;
  selected: string | null;
  onOpen: (target: SourceTarget) => void;
  onCorrect: ((fact: ClaimFact) => void) | null;
}) {
  const claimFacts = (facts?.facts ?? []).filter((fact) => fact.documentId === null);
  return (
    <Section
      icon={FileText}
      title="Claim facts"
      link={
        facts?.sequence ? (
          <span className="text-xs text-ink-muted">Reading {facts.sequence}</span>
        ) : null
      }
    >
      {claimFacts.length === 0 ? (
        <p className="text-[13px] text-ink-muted">No facts yet.</p>
      ) : (
        <dl className="divide-y divide-line">
          {claimFacts.map((fact) => {
            const value = displayValue(fact, currency);
            const source = fact.sources.find((candidate) => candidate.page !== null);
            const key = `fact-${fact.id}`;
            return (
              <div key={fact.id} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 py-2" data-testid="workspace-fact">
                <dt className="text-[13px] text-ink-muted">{FACT_NAME_LABELS[fact.name] ?? fact.name}</dt>
                <dd className="flex min-w-0 flex-wrap items-center justify-end gap-1.5 text-right text-[13px]">
                  {fact.state === "PRESENT" && value ? (
                    <span className="font-medium break-words">{value}</span>
                  ) : (
                    <Badge tone={fact.state === "CONFLICTING" ? "critical" : "caution"}>{FACT_STATE_LABELS[fact.state]}</Badge>
                  )}
                  {fact.correction ? (
                    <span title={`${fact.correction.correctedBy}: ${fact.correction.reason}`}>
                      <Badge tone="accent">Corrected</Badge>
                    </span>
                  ) : null}
                  {source?.page ? (
                    <SourceButton
                      target={{ key, documentId: source.documentId, page: source.page, region: source.region, label: `${FACT_NAME_LABELS[fact.name] ?? fact.name}, page ${source.page}` }}
                      selected={selected === key}
                      onOpen={onOpen}
                    >
                      Source
                    </SourceButton>
                  ) : null}
                  {onCorrect ? (
                    <button
                      type="button"
                      onClick={() => onCorrect(fact)}
                      aria-label={`Correct ${FACT_NAME_LABELS[fact.name] ?? fact.name}`}
                      className="rounded p-1 text-ink-muted hover:bg-subtle hover:text-ink"
                    >
                      <PencilLine aria-hidden className="size-3.5" />
                    </button>
                  ) : null}
                </dd>
              </div>
            );
          })}
        </dl>
      )}
    </Section>
  );
}

export function AnalysisPanel({ claimId, assessment }: { claimId: string; assessment: PolicyAssessment | null }) {
  return (
    <Section
      icon={Scale}
      title="Policy analysis, by AI"
      link={
        assessment ? (
          <Link href={`/claims/${claimId}/assessment`} className="text-xs font-medium text-accent hover:text-accent-hover">
            Open with citations
          </Link>
        ) : null
      }
    >
      {assessment === null ? (
        <p className="text-[13px] text-ink-muted">No policy analysis. The claim&apos;s policy version may not be published.</p>
      ) : (
        <ul className="space-y-2">
          {assessment.findings.map((finding) => (
            <li key={finding.position} className="flex items-start justify-between gap-3 text-[13px]">
              <span className="min-w-0">{finding.question}</span>
              <Badge tone={finding.status === "SUPPORTED" ? "positive" : finding.status === "POTENTIALLY_EXCLUDED" ? "critical" : "caution"}>
                {FINDING_STATUS_LABELS[finding.status]}
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

export function RiskPanel({
  claimId,
  screening,
  selected,
  onOpen,
}: {
  claimId: string;
  screening: RiskScreening | null;
  selected: string | null;
  onOpen: (target: SourceTarget) => void;
}) {
  return (
    <Section
      icon={ShieldAlert}
      title="Risk screening"
      link={
        screening ? (
          <Link href={`/claims/${claimId}/risk`} className="text-xs font-medium text-accent hover:text-accent-hover">
            Open full screening
          </Link>
        ) : null
      }
    >
      {screening === null ? (
        <p className="text-[13px] text-ink-muted">Not screened yet.</p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2 text-[13px]">
            <Badge tone={BAND_TONES[screening.band]}>{RISK_BAND_LABELS[screening.band]}</Badge>
            <span className="tabular-nums">{screening.score} / 100</span>
            <span className="text-ink-muted">
              · {screening.detectors.filter((detector) => detector.flagged).length} of 2 detectors flagged it
            </span>
          </div>
          {screening.indicators.length > 0 ? (
            <ul className="mt-3 space-y-2">
              {screening.indicators.map((indicator) => {
                const evidence = indicator.evidence.find((item) => item.documentId);
                const key = `risk-${indicator.position}`;
                return (
                  <li key={indicator.position} className="text-[13px]">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="font-medium">{indicator.title}</span>
                      <Badge tone={indicator.kind === "FRAUD_INDICATOR" ? "critical" : "neutral"}>{INDICATOR_KIND_LABELS[indicator.kind]}</Badge>
                      {evidence?.documentId ? (
                        <SourceButton
                          target={{ key, documentId: evidence.documentId, page: evidence.page ?? 1, region: evidence.region, label: evidence.label }}
                          selected={selected === key}
                          onOpen={onOpen}
                        >
                          Evidence
                        </SourceButton>
                      ) : null}
                    </div>
                    <p className="text-xs text-ink-muted">{indicator.explanation}</p>
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className="mt-2 text-[13px] text-ink-muted">No rule raised an indicator.</p>
          )}
          <p className="mt-3 text-xs text-ink-muted">Screening signals for review. None of them is a finding of fraud.</p>
        </>
      )}
    </Section>
  );
}

/** What is not settled: facts the documents do not establish, and the analysis's open questions. */
export function QuestionsPanel({ facts, assessment }: { facts: ClaimFacts | null; assessment: PolicyAssessment | null }) {
  const unsettled = (facts?.facts ?? []).filter((fact) => fact.documentId === null && fact.critical && fact.state !== "PRESENT");
  const questions = (assessment?.findings ?? []).flatMap((finding) => finding.unresolved.map((question) => ({ finding, question })));
  return (
    <Section icon={CircleHelp} title="Open questions">
      {unsettled.length === 0 && questions.length === 0 ? (
        <p className="text-[13px] text-ink-muted">None.</p>
      ) : (
        <ul className="list-disc space-y-1 pl-5 text-[13px]">
          {unsettled.map((fact) => (
            <li key={fact.id}>
              {FACT_NAME_LABELS[fact.name] ?? fact.name}: {FACT_STATE_LABELS[fact.state].toLowerCase()} in the documents.
            </li>
          ))}
          {questions.map(({ finding, question }) => (
            <li key={`${finding.position}-${question}`}>{question}</li>
          ))}
        </ul>
      )}
    </Section>
  );
}

/** Outcomes, findings, notes, and the path the claim took through the queues. */
export function RecordPanel({ review }: { review: ReviewWorkspace }) {
  return (
    <div className="space-y-4">
      {review.outcomes.length > 0 ? (
        <Panel className="p-5" >
          <h2 className="text-[13px] font-medium">Authorized outcome</h2>
          <ol className="mt-2 space-y-3">
            {[...review.outcomes].reverse().map((outcome) => (
              <li key={outcome.id} className={`text-[13px] ${outcome.current ? "" : "text-ink-muted"}`} data-testid="outcome">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={outcome.decision === "DENIED" ? "critical" : outcome.decision === "APPROVED" ? "positive" : "caution"}>
                    {DECISION_LABELS[outcome.decision]}
                  </Badge>
                  {outcome.approvedAmount ? <span className="font-medium tabular-nums">{formatMoney(outcome.approvedAmount, outcome.currency)}</span> : null}
                  {outcome.current ? null : <Badge>Superseded</Badge>}
                </div>
                <p className="mt-1">{outcome.rationale}</p>
                <p className="mt-1 text-xs text-ink-muted">
                  {outcome.supersedesId ? "Revised" : "Decided"} by {outcome.decidedBy}, {formatDateTime(outcome.decidedAt)}. {AI_AGREEMENT_LABELS[outcome.aiAgreement]}.
                </p>
              </li>
            ))}
          </ol>
        </Panel>
      ) : null}
      {review.findings.length > 0 ? (
        <Panel className="p-5">
          <h2 className="text-[13px] font-medium">Investigation</h2>
          {review.findings.map((finding) => (
            <div key={finding.id} className="mt-2 text-[13px]" data-testid="investigation-finding">
              <Badge tone={finding.finding === "CONFIRMED_FRAUD" ? "critical" : finding.finding === "NO_CONCERN" ? "positive" : "caution"}>
                {FINDING_LABELS[finding.finding]}
              </Badge>
              <p className="mt-1">{finding.rationale}</p>
              <p className="mt-1 text-xs text-ink-muted">
                Recorded by {finding.investigator}, {formatDateTime(finding.createdAt)}. A person&apos;s finding, after investigation.
              </p>
            </div>
          ))}
        </Panel>
      ) : null}
      <Panel className="p-5">
        <h2 className="text-[13px] font-medium">Review history</h2>
        <ol className="mt-2 space-y-2 text-[13px]">
          {review.history.map((task) => (
            <li key={task.id} className="flex flex-wrap justify-between gap-x-3" data-testid="task-history">
              <span>
                {QUEUE_LABELS[task.queue]} · {TASK_REASON_LABELS[task.reason]}
                {task.assigneeName ? <span className="text-ink-muted"> · {task.assigneeName}</span> : null}
              </span>
              <span className="text-xs text-ink-muted">
                {task.completion ? TASK_COMPLETION_LABELS[task.completion] : task.status === "CLAIMED" ? "In progress" : "Waiting"} ·{" "}
                {formatDateTime(task.completedAt ?? task.claimedAt ?? task.createdAt)}
              </span>
            </li>
          ))}
        </ol>
      </Panel>
    </div>
  );
}
