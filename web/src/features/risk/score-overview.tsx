import { Route } from "lucide-react";
import { Badge } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { RISK_BAND_LABELS, REVIEW_ROUTE_LABELS, RISK_CATEGORY_LABELS } from "@/lib/labels";
import type { RiskScreening } from "@/lib/types";
import { BAND_TONES } from "./risk-format";

/** The band, the route it sets, and a bar showing where the score sits between the band thresholds. */
export function ScoreOverview({ screening }: { screening: RiskScreening }) {
  const { thresholds, score } = screening;
  const position = (value: number) => `${(Math.min(value, thresholds.maximum) / thresholds.maximum) * 100}%`;
  const statistical = screening.score - screening.rulesOnlyScore;
  const flagged = screening.detectors.filter((detector) => detector.flagged).length;

  return (
    <Panel className="p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-muted uppercase">Screening result</p>
          <div className="mt-1.5 flex flex-wrap items-center gap-3">
            <Badge tone={BAND_TONES[screening.band]}>
              <span data-testid="risk-band">{RISK_BAND_LABELS[screening.band]}</span>
            </Badge>
            <p className="text-2xl font-semibold tracking-tight tabular-nums">
              <span data-testid="risk-score">{score}</span>
              <span className="text-sm font-normal text-ink-muted"> / {thresholds.maximum}</span>
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2 rounded-control bg-subtle px-3 py-2 text-sm">
          <Route aria-hidden className="size-4 text-ink-muted" />
          <span>
            Review route: <span className="font-medium" data-testid="review-route">{REVIEW_ROUTE_LABELS[screening.route]}</span>
          </span>
        </div>
      </div>

      <div className="mt-5" aria-hidden>
        <div className="relative h-2 overflow-hidden rounded-full bg-subtle">
          <div className="absolute inset-y-0 left-0 bg-positive/35" style={{ width: position(thresholds.mediumFrom) }} />
          <div
            className="absolute inset-y-0 bg-caution/35"
            style={{ left: position(thresholds.mediumFrom), width: `calc(${position(thresholds.highFrom)} - ${position(thresholds.mediumFrom)})` }}
          />
          <div className="absolute inset-y-0 right-0 bg-critical/35" style={{ left: position(thresholds.highFrom) }} />
        </div>
        <div className="relative h-4">
          <span className="absolute top-0 -ml-1.5 size-3 rotate-45 bg-ink" style={{ left: position(score) }} />
        </div>
      </div>
      <p className="text-xs text-ink-muted tabular-nums">
        Low below {thresholds.mediumFrom} · Medium {thresholds.mediumFrom} to {thresholds.highFrom - 1} · High {thresholds.highFrom} and above
      </p>

      <p className="mt-4 text-sm">
        Rules alone give <span className="font-medium tabular-nums">{screening.rulesOnlyScore}</span> ({RISK_BAND_LABELS[screening.rulesOnlyBand].toLowerCase()}).{" "}
        {statistical > 0 ? (
          <>
            {RISK_CATEGORY_LABELS.STATISTICAL} added <span className="font-medium tabular-nums">{statistical}</span>
            {screening.rulesOnlyBand !== screening.band ? ", which moved the claim to a higher band." : "."}
          </>
        ) : flagged > 0 ? (
          "The detectors flagged it too, but the score was already at its maximum."
        ) : (
          "Neither detector flagged it, so statistical anomalies added nothing."
        )}
      </p>
      <p className="mt-2 text-xs text-ink-muted">
        A screening score, not a probability of fraud. The weights are project configuration set by judgment ({screening.versions.scoring}),
        not learned from outcomes. No band changes the claim&apos;s outcome: a high band sends it to senior review.
      </p>
    </Panel>
  );
}
