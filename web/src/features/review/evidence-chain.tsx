"use client";

import { Bot, FileText, Gavel, ScrollText, ShieldAlert, Users } from "lucide-react";
import Link from "next/link";
import { useRef, useState, type ReactNode } from "react";
import { SourcePage } from "@/components/source-page";
import { Badge, EmptyState } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import {
  AI_AGREEMENT_LABELS,
  DECISION_LABELS,
  DOCUMENT_TYPE_LABELS,
  FACT_NAME_LABELS,
  FACT_STATE_LABELS,
  FINDING_LABELS,
  QUEUE_LABELS,
  RISK_BAND_LABELS,
  TASK_COMPLETION_LABELS,
  TASK_REASON_LABELS,
} from "@/lib/labels";
import type { EvidenceChain } from "@/lib/types";
import { BAND_TONES } from "@/features/risk/risk-format";

/** Dates and amounts as reviewers read them elsewhere. Other facts as stated. */
function displayValue(name: string, value: string | null, currency: string): string | null {
  if (value === null) {
    return null;
  }
  if (name.endsWith("_date")) {
    return formatDate(value);
  }
  return name.endsWith("_amount") ? formatMoney(value, currency) : value;
}

interface Selected {
  key: string;
  documentId: string;
  page: number;
  region: number[] | null;
  label: string;
}

function ChainStep({ step, icon: Icon, title, children }: { step: number; icon: typeof Gavel; title: string; children: ReactNode }) {
  return (
    <li className="relative pl-10" data-testid="chain-link">
      <span className="absolute top-0 left-0 flex size-7 items-center justify-center rounded-full bg-accent-soft text-accent">
        <Icon aria-hidden className="size-4" />
      </span>
      <p className="text-xs text-ink-muted">Step {step}</p>
      <h2 className="text-[15px] font-semibold">{title}</h2>
      <div className="mt-2">{children}</div>
    </li>
  );
}

/** From the authorized outcome back to the source pages, in order, with nothing summarized away. */
export function EvidenceChainView({ claimId, chain }: { claimId: string; chain: EvidenceChain }) {
  const [selected, setSelected] = useState<Selected | null>(null);
  const pane = useRef<HTMLDivElement>(null);
  const evidence = chain.evidence;
  const documents = new Map((evidence?.documents ?? []).map((document) => [document.id, document]));

  function open(next: Selected) {
    setSelected(next);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      requestAnimationFrame(() => pane.current?.scrollIntoView({ block: "start" }));
    }
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
      <ol className="min-w-0 space-y-8 border-l border-line pl-0 [&>li]:-ml-3.5">
        <ChainStep step={1} icon={Gavel} title="Authorized outcome">
          {chain.outcomes.length === 0 ? (
            <p className="text-[13px] text-ink-muted">No outcome yet. The rest of the chain shows the claim as it stands.</p>
          ) : (
            <ol className="space-y-3">
              {[...chain.outcomes].reverse().map((outcome) => (
                <li key={outcome.id} className={`rounded-panel border border-line bg-surface p-4 text-[13px] ${outcome.current ? "" : "opacity-70"}`}>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={outcome.decision === "DENIED" ? "critical" : outcome.decision === "APPROVED" ? "positive" : "caution"}>
                      <span data-testid={outcome.current ? "chain-decision" : undefined}>{DECISION_LABELS[outcome.decision]}</span>
                    </Badge>
                    {outcome.approvedAmount ? <span className="font-medium tabular-nums">{formatMoney(outcome.approvedAmount, outcome.currency)}</span> : null}
                    {outcome.current ? <Badge tone="accent">Current</Badge> : <Badge>Superseded</Badge>}
                  </div>
                  <p className="mt-2">{outcome.rationale}</p>
                  <p className="mt-1 text-xs text-ink-muted">
                    {outcome.supersedesId ? "Revision" : "Decision"} by {outcome.decidedBy}, {formatDateTime(outcome.decidedAt)}.{" "}
                    {AI_AGREEMENT_LABELS[outcome.aiAgreement]}.
                  </p>
                </li>
              ))}
            </ol>
          )}
        </ChainStep>

        <ChainStep step={2} icon={Users} title="People and the path through review">
          <ol className="space-y-1.5 text-[13px]">
            {chain.tasks.map((task) => (
              <li key={task.id} className="flex flex-wrap justify-between gap-x-3">
                <span>
                  {QUEUE_LABELS[task.queue]} · {TASK_REASON_LABELS[task.reason]}
                  {task.assigneeName ? <span className="text-ink-muted"> · {task.assigneeName}</span> : null}
                </span>
                <span className="text-xs text-ink-muted">
                  {task.completion ? TASK_COMPLETION_LABELS[task.completion] : "Open"} · {formatDateTime(task.completedAt ?? task.createdAt)}
                </span>
              </li>
            ))}
          </ol>
          {chain.findings.map((finding) => (
            <p key={finding.id} className="mt-2 text-[13px]">
              <Badge tone={finding.finding === "CONFIRMED_FRAUD" ? "critical" : "neutral"}>{FINDING_LABELS[finding.finding]}</Badge>{" "}
              {finding.rationale} <span className="text-xs text-ink-muted">· {finding.investigator}</span>
            </p>
          ))}
          {chain.requests.map((request) => (
            <p key={request.id} className="mt-2 text-[13px] text-ink-muted">
              Requested {request.documentTypes.map((type) => DOCUMENT_TYPE_LABELS[type].toLowerCase()).join(", ")} ({request.status.toLowerCase()}): “
              {request.message}”
            </p>
          ))}
          <p className="mt-2 text-xs text-ink-muted">{chain.notes} review notes, on the workspace.</p>
        </ChainStep>

        <ChainStep step={3} icon={ScrollText} title="What it was decided with">
          {evidence === null ? (
            <p className="text-[13px] text-ink-muted">No facts were read.</p>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              <Panel className="p-4 text-[13px]">
                <p className="font-medium">Policy analysis, by AI</p>
                {evidence.policyAssessment ? (
                  <>
                    <p className="mt-1 text-ink-muted">
                      {evidence.policyAssessment.supported} of {evidence.policyAssessment.findings} issues supported by the wording ·{" "}
                      {evidence.policyAssessment.pipelineVersion}
                    </p>
                    <Link href={`/claims/${claimId}/assessment`} className="mt-1 inline-block text-xs font-medium text-accent">
                      Open with citations
                    </Link>
                  </>
                ) : (
                  <p className="mt-1 text-ink-muted">None for these facts.</p>
                )}
              </Panel>
              <Panel className="p-4 text-[13px]">
                <p className="flex items-center gap-1.5 font-medium">
                  <ShieldAlert aria-hidden className="size-4 text-ink-muted" />
                  Risk screening
                </p>
                {evidence.riskScreening ? (
                  <>
                    <p className="mt-1 flex flex-wrap items-center gap-1.5">
                      <Badge tone={BAND_TONES[evidence.riskScreening.band]}>{RISK_BAND_LABELS[evidence.riskScreening.band]}</Badge>
                      <span className="text-ink-muted">
                        {evidence.riskScreening.score} / 100 · {evidence.riskScreening.indicators} indicators · {evidence.riskScreening.rulesetVersion},{" "}
                        {evidence.riskScreening.modelVersion}
                      </span>
                    </p>
                    <Link href={`/claims/${claimId}/risk`} className="mt-1 inline-block text-xs font-medium text-accent">
                      Open full screening
                    </Link>
                  </>
                ) : (
                  <p className="mt-1 text-ink-muted">None for these facts.</p>
                )}
              </Panel>
            </div>
          )}
        </ChainStep>

        {evidence ? (
          <ChainStep step={4} icon={FileText} title={`Facts, from reading ${evidence.sequence} on ${formatDate(evidence.consolidatedAt.slice(0, 10))}`}>
            <ul className="divide-y divide-line rounded-panel border border-line bg-surface">
              {evidence.facts.map((fact) => (
                <li key={fact.name} className="px-4 py-2.5 text-[13px]" data-testid="chain-fact">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-ink-muted">{FACT_NAME_LABELS[fact.name] ?? fact.name}</span>
                    <span className="flex items-center gap-1.5">
                      {fact.state === "PRESENT" ? <span className="font-medium">{displayValue(fact.name, fact.value, chain.currency)}</span> : <Badge tone="caution">{FACT_STATE_LABELS[fact.state]}</Badge>}
                      {fact.corrected ? <Badge tone="accent">Corrected by a reviewer</Badge> : null}
                    </span>
                  </div>
                  {fact.sources.length > 0 ? (
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {fact.sources.map((source, index) => {
                        const document = documents.get(source.documentId);
                        const label = `${document ? DOCUMENT_TYPE_LABELS[document.documentType] : "Document"}, page ${source.page ?? 1}`;
                        const key = `${fact.name}-${index}`;
                        return (
                          <button
                            key={key}
                            type="button"
                            aria-pressed={selected?.key === key}
                            onClick={() => open({ key, documentId: source.documentId, page: source.page ?? 1, region: source.region, label })}
                            className={`rounded border px-1.5 py-0.5 text-xs hover:bg-subtle ${selected?.key === key ? "border-accent text-accent" : "border-line text-ink-muted"}`}
                          >
                            {label}
                          </button>
                        );
                      })}
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          </ChainStep>
        ) : null}

        {evidence ? (
          <ChainStep step={5} icon={Bot} title="AI runs">
            <ol className="divide-y divide-line rounded-panel border border-line bg-surface text-[13px]">
              {evidence.modelRuns.map((run, index) => (
                <li key={index} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 px-4 py-2" data-testid="chain-run">
                  <span>
                    <span className="font-medium">{run.task}</span>
                    <span className="text-ink-muted"> · {run.promptVersion} · {run.outcome.toLowerCase()}</span>
                  </span>
                  <span className="min-w-0 text-xs break-words text-ink-muted">
                    {run.model} · {formatDateTime(run.recordedAt)}
                  </span>
                </li>
              ))}
            </ol>
            {evidence.modelRuns.length === 0 ? <p className="mt-2 text-[13px] text-ink-muted">No model calls recorded.</p> : null}
          </ChainStep>
        ) : null}

        {evidence ? (
          <ChainStep step={6} icon={FileText} title="Source documents">
            <ul className="space-y-1.5 text-[13px]">
              {evidence.documents.map((document) => (
                <li key={document.id} className="flex flex-wrap gap-x-2" data-testid="chain-document">
                  <Link href={`/documents/${document.id}?back=/claims/${claimId}/evidence`} className="font-medium hover:text-accent">
                    {document.filename}
                  </Link>
                  <span className="basis-full text-xs text-ink-muted">
                    {DOCUMENT_TYPE_LABELS[document.documentType]} · SHA-256 <code className="break-all">{document.sha256.slice(0, 16)}…</code> ·{" "}
                    {formatDateTime(document.uploadedAt)}
                  </span>
                </li>
              ))}
            </ul>
          </ChainStep>
        ) : null}
      </ol>
      <div ref={pane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
        <SectionTitle>Source page</SectionTitle>
        {selected ? (
          <>
            <p className="mb-2 text-[13px] font-medium" data-testid="source-label">
              {selected.label}
            </p>
            <SourcePage documentId={selected.documentId} page={selected.page} region={selected.region} label={selected.label} />
          </>
        ) : (
          <Panel>
            <EmptyState icon={FileText} title="Choose a source">
              Each fact&apos;s source page opens here, with the text outlined.
            </EmptyState>
          </Panel>
        )}
      </div>
    </div>
  );
}
