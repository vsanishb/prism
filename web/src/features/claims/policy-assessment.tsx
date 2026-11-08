"use client";

import { Calculator, CircleAlert, FileSearch, HelpCircle, Info, Quote, Scale } from "lucide-react";
import { useRef, useState, type ReactNode } from "react";
import { SourcePage } from "@/components/source-page";
import { Badge, EmptyState } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import {
  FACT_NAME_LABELS,
  FACT_STATE_LABELS,
  FINDING_PROBLEM_LABELS,
  FINDING_STATUS_LABELS,
  ISSUE_KIND_LABELS,
  VALUE_ROLE_LABELS,
} from "@/lib/labels";
import type { AssessmentCitation, AssessmentFinding, FindingStatus, PolicyAssessment } from "@/lib/types";

const TONES: Record<FindingStatus, "positive" | "caution" | "critical" | "neutral" | "accent"> = {
  SUPPORTED: "positive",
  LIMITED: "caution",
  POTENTIALLY_EXCLUDED: "critical",
  INSUFFICIENT_EVIDENCE: "neutral",
  NEEDS_HUMAN_INTERPRETATION: "accent",
};

interface Source {
  id: string;
  title: string;
  page: number;
  region: AssessmentCitation["region"];
  quote: string;
}

function citationName(citation: AssessmentCitation): string {
  return [citation.key, citation.title].filter(Boolean).join(" ") || "Cited provision";
}

function Part({ icon: Icon, title, children }: { icon: typeof Info; title: string; children: ReactNode }) {
  return (
    <section className="mt-4">
      <h3 className="mb-1.5 flex items-center gap-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">
        <Icon aria-hidden className="size-3.5" />
        {title}
      </h3>
      {children}
    </section>
  );
}

function FindingCard({
  finding,
  currency,
  selected,
  onOpen,
}: {
  finding: AssessmentFinding;
  currency: string;
  selected: string | null;
  onOpen: (source: Source) => void;
}) {
  return (
    <li className="p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-medium text-ink-muted">{ISSUE_KIND_LABELS[finding.kind]}</p>
          <h2 className="mt-0.5 font-medium">{finding.question}</h2>
        </div>
        <Badge tone={TONES[finding.status]}>{FINDING_STATUS_LABELS[finding.status]}</Badge>
      </div>

      {finding.proposedStatus ? (
        <div className="mt-3 flex gap-2 rounded-control bg-subtle px-3 py-2 text-sm text-ink-muted" role="note">
          <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div>
            <p>
              The model proposed <span className="font-medium text-ink">{FINDING_STATUS_LABELS[finding.proposedStatus]}</span>. Prism
              changed it because a check failed:
            </p>
            <ul className="mt-0.5 list-disc pl-5">
              {finding.problems.map((problem) => (
                <li key={problem}>{FINDING_PROBLEM_LABELS[problem] ?? problem}</li>
              ))}
            </ul>
          </div>
        </div>
      ) : null}

      <Part icon={Info} title="Claim facts used">
        {finding.facts.length === 0 ? (
          <p className="text-sm text-ink-muted">None named.</p>
        ) : (
          <ul className="space-y-0.5 text-sm">
            {finding.facts.map((fact) => (
              <li key={fact.id}>
                <span className="text-ink-muted">{FACT_NAME_LABELS[fact.name] ?? fact.name}: </span>
                {fact.state === "PRESENT" ? fact.value : <Badge tone="caution">{FACT_STATE_LABELS[fact.state]}</Badge>}
              </li>
            ))}
          </ul>
        )}
      </Part>

      <Part icon={Quote} title="Policy evidence">
        {finding.citations.length === 0 ? (
          <p className="text-sm text-ink-muted">No passage of the wording supports this finding.</p>
        ) : (
          <ul className="space-y-2">
            {finding.citations.map((citation, index) => {
              const id = `${finding.position}-${index}`;
              return (
                <li key={id}>
                  <button
                    type="button"
                    aria-pressed={selected === id}
                    onClick={() =>
                      onOpen({ id, title: citationName(citation), page: citation.pageStart, region: citation.region, quote: citation.quote })
                    }
                    className={`block w-full rounded-control border px-3 py-2 text-left hover:bg-subtle ${selected === id ? "border-accent bg-accent-soft/40" : "border-line"}`}
                  >
                    <span className="text-sm font-medium text-accent">
                      {citationName(citation)} · Page {citation.pageStart}
                    </span>
                    <blockquote className="mt-0.5 text-sm text-ink">“{citation.quote}”</blockquote>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </Part>

      {finding.reasoning ? (
        <Part icon={Scale} title="Reasoning, by AI">
          <p className="text-sm">{finding.reasoning}</p>
        </Part>
      ) : null}

      {finding.calculations.length > 0 ? (
        <Part icon={Calculator} title="Calculated by Prism">
          <ul className="space-y-3">
            {finding.calculations.map((calculation, index) => (
              <li key={index} className="rounded-control bg-subtle px-3 py-2.5 text-sm" data-testid="calculation">
                <p className="font-medium">{VALUE_ROLE_LABELS[calculation.role]}</p>
                <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
                  {calculation.inputs.map(([label, value]) => (
                    <div key={label} className="contents">
                      <dt className="text-ink-muted">{label}</dt>
                      <dd className="min-w-0 break-words">{value}</dd>
                    </div>
                  ))}
                </dl>
                {calculation.steps.length > 0 ? (
                  <ol className="mt-1.5 list-decimal space-y-0.5 pl-5 text-ink-muted">
                    {calculation.steps.map((step) => (
                      <li key={step}>{step}</li>
                    ))}
                  </ol>
                ) : null}
                <p className="mt-1.5">
                  {calculation.resultAmount ? <span className="font-medium">{formatMoney(calculation.resultAmount, currency)}. </span> : null}
                  {calculation.resultDate && !calculation.resultAmount ? (
                    <span className="font-medium">{formatDate(calculation.resultDate)}. </span>
                  ) : null}
                  {calculation.outcome}
                </p>
              </li>
            ))}
          </ul>
        </Part>
      ) : null}

      <Part icon={HelpCircle} title="Open questions">
        {finding.unresolved.length === 0 ? (
          <p className="text-sm text-ink-muted">None raised.</p>
        ) : (
          <ul className="list-disc space-y-0.5 pl-5 text-sm">
            {finding.unresolved.map((question) => (
              <li key={question}>{question}</li>
            ))}
          </ul>
        )}
      </Part>

      {finding.queries.length > 0 ? (
        <p className="mt-4 text-xs text-ink-muted">
          Searched the wording for: {finding.queries.map((query) => `“${query}”`).join(", ")}
        </p>
      ) : null}
    </li>
  );
}

/** The Policy Agent's analysis, issue by issue. It assists a reviewer and decides nothing. */
export function PolicyAssessmentView({ assessment, currency }: { assessment: PolicyAssessment; currency: string }) {
  const [source, setSource] = useState<Source | null>(null);
  const sourcePane = useRef<HTMLDivElement>(null);

  function open(next: Source) {
    setSource(next);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      requestAnimationFrame(() => sourcePane.current?.scrollIntoView({ block: "start" }));
    }
  }

  return (
    <>
      <div className="mb-6 space-y-3">
        {!assessment.current ? (
          <div role="status" className="rounded-control bg-caution-soft px-3 py-2.5 text-sm text-caution">
            The claim&apos;s facts have changed since this analysis. A new one follows when processing finishes.
          </div>
        ) : null}
        {assessment.degradedSearches > 0 ? (
          <p className="text-sm text-ink-muted">
            {assessment.degradedSearches} {assessment.degradedSearches === 1 ? "search" : "searches"} ran without every stage, so some
            evidence may have been missed.
          </p>
        ) : null}
      </div>
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <Panel className="min-w-0 self-start">
          <ol className="divide-y divide-line">
            {assessment.findings.map((finding) => (
              <FindingCard key={finding.position} finding={finding} currency={currency} selected={source?.id ?? null} onOpen={open} />
            ))}
          </ol>
        </Panel>
        <div ref={sourcePane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
          <SectionTitle>Policy wording</SectionTitle>
          {source && assessment.wordingDocumentId ? (
            <>
              <p className="mb-2 text-sm font-medium">
                {source.title} · Page {source.page}
              </p>
              <SourcePage
                documentId={assessment.wordingDocumentId}
                page={source.page}
                region={source.region}
                label={`${source.title}, page ${source.page}`}
              />
            </>
          ) : (
            <Panel>
              <EmptyState icon={FileSearch} title="Choose a citation">
                Its page of the policy wording opens here, with the provision outlined.
              </EmptyState>
            </Panel>
          )}
          <p className="mt-3 text-xs text-ink-muted">
            Cover counted from {formatDate(assessment.coverStart)}. Analyzed {formatDateTime(assessment.createdAt)}.
          </p>
        </div>
      </div>
    </>
  );
}
