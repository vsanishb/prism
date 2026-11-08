import { Sparkles } from "lucide-react";
import { Fragment } from "react";
import { Panel } from "@/components/ui/layout";
import { RISK_SUMMARY_PROBLEMS } from "@/lib/labels";
import type { RiskSummary } from "@/lib/types";

const REFERENCE = /(\[S\d+\])/;

/**
 * The model's summary, kept visibly apart from the screening. Each [S1] reference links to the
 * signal it cites, so a reader can check every sentence against its evidence.
 */
export function AiSummary({ summary, onCite }: { summary: RiskSummary | null; onCite: (signalId: string) => void }) {
  return (
    <Panel className="p-5">
      <h2 className="flex items-center gap-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">
        <Sparkles aria-hidden className="size-3.5" />
        Summary, written by AI
      </h2>
      {summary?.status === "AVAILABLE" && summary.text ? (
        <>
          <p className="mt-2 text-sm leading-relaxed" data-testid="risk-summary">
            {summary.text.split(REFERENCE).map((part, index) => {
              const match = /^\[(S\d+)\]$/.exec(part);
              const id = match?.[1];
              return id ? (
                <button
                  key={index}
                  type="button"
                  onClick={() => onCite(id)}
                  className="mx-0.5 rounded bg-accent-soft px-1 text-xs font-medium text-accent hover:bg-accent/15"
                  aria-label={`Go to signal ${id}`}
                >
                  {id}
                </button>
              ) : (
                <Fragment key={index}>{part}</Fragment>
              );
            })}
          </p>
          <p className="mt-3 text-xs text-ink-muted">
            Written by AI from the signals below only. Prism checked that it cites real signals, uses no number they do not contain, and
            makes no accusation or decision. It cannot change the score, band, or route.
          </p>
        </>
      ) : (
        <p className="mt-2 text-sm text-ink-muted">
          {summary?.status === "NOT_NEEDED"
            ? "Nothing to summarize: no rule or detector raised a signal."
            : `${summary ? (RISK_SUMMARY_PROBLEMS[summary.problem ?? ""] ?? RISK_SUMMARY_PROBLEMS.default) : "No summary yet."} The screening below is complete without it.`}
        </p>
      )}
    </Panel>
  );
}
