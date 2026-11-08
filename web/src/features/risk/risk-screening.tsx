"use client";

import { FileSearch } from "lucide-react";
import { useRef, useState } from "react";
import { SourcePage } from "@/components/source-page";
import { EmptyState } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { formatDateTime } from "@/lib/format";
import type { RiskScreening } from "@/lib/types";
import { AiSummary } from "./ai-summary";
import { AnomalyResults } from "./anomaly-results";
import { RuleChecklist, RuleIndicators, type EvidenceSource } from "./rule-indicators";
import { ScoreBreakdown } from "./score-breakdown";
import { ScoreOverview } from "./score-overview";

/**
 * The risk screening, in the order a reviewer needs it: the result and its route, an optional AI
 * summary, what the rules found with evidence, what the detectors found, and how the score was built.
 */
export function RiskScreeningView({ screening, currency }: { screening: RiskScreening; currency: string }) {
  const [source, setSource] = useState<EvidenceSource | null>(null);
  const sourcePane = useRef<HTMLDivElement>(null);

  function open(next: EvidenceSource) {
    setSource(next);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      requestAnimationFrame(() => sourcePane.current?.scrollIntoView({ block: "start" }));
    }
  }

  function cite(signalId: string) {
    document.getElementById(`signal-${signalId}`)?.scrollIntoView({ block: "start", behavior: "smooth" });
  }

  return (
    <>
      {!screening.current ? (
        <div role="status" className="mb-6 rounded-control bg-caution-soft px-3 py-2.5 text-sm text-caution">
          The claim&apos;s facts have changed since this screening. A new one follows when processing finishes.
        </div>
      ) : null}
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-8">
          <ScoreOverview screening={screening} />
          <AiSummary summary={screening.summary} onCite={cite} />
          <section>
            <SectionTitle>Found by rules</SectionTitle>
            <RuleIndicators indicators={screening.indicators} selected={source?.key ?? null} onOpen={open} />
            <div className="mt-4">
              <RuleChecklist rules={screening.rules} open={screening.indicators.length === 0} />
            </div>
          </section>
          <section>
            <SectionTitle>Statistical anomaly detection</SectionTitle>
            <AnomalyResults screening={screening} currency={currency} />
          </section>
          <section>
            <SectionTitle>How the score was built</SectionTitle>
            <ScoreBreakdown screening={screening} />
          </section>
          <p className="text-xs text-ink-muted">Screened {formatDateTime(screening.createdAt)}.</p>
        </div>
        <div ref={sourcePane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
          <SectionTitle>Evidence</SectionTitle>
          {source ? (
            <>
              <p className="mb-2 text-sm font-medium">
                {source.label}
                {source.label.includes("page") ? "" : ` · Page ${source.page}`}
              </p>
              <SourcePage documentId={source.documentId} page={source.page} region={source.region} label={source.label} />
            </>
          ) : (
            <Panel>
              <EmptyState icon={FileSearch} title="Choose a piece of evidence">
                Its page of the claim document opens here, with the text outlined where the page has a text layer.
              </EmptyState>
            </Panel>
          )}
        </div>
      </div>
    </>
  );
}
