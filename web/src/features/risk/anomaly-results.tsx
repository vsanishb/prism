import { Activity, FlaskConical } from "lucide-react";
import { Badge } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { DETECTOR_LABELS, RISK_FEATURE_LABELS } from "@/lib/labels";
import type { RiskDetectorResult, RiskScreening } from "@/lib/types";
import { formatFeature, isUnusual, percentileText } from "./risk-format";

function DetectorCard({ result }: { result: RiskDetectorResult }) {
  const labels = DETECTOR_LABELS[result.detector];
  return (
    <div
      id={result.signalId ? `signal-${result.signalId}` : undefined}
      className="scroll-mt-6 rounded-panel border border-line bg-surface p-4"
      data-testid={`detector-${result.detector}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-medium text-ink-muted">
            {result.signalId ? <span className="mr-1.5 text-accent">{result.signalId}</span> : null}
            {labels.name}
          </p>
          <p className="mt-0.5 text-[13px] font-medium">{labels.question}</p>
        </div>
        <Badge tone={result.flagged ? "caution" : "positive"}>{result.flagged ? "Statistically unusual" : "Within the normal range"}</Badge>
      </div>
      <p className="mt-2 text-[13px]">{percentileText(Number(result.percentile))}.</p>
      <p className="mt-1 text-xs text-ink-muted">
        {result.flagged
          ? `Beyond this detector's threshold, so it adds ${result.points} points.`
          : "Below this detector's threshold, so it adds nothing."}
      </p>
    </div>
  );
}

/**
 * Isolation Forest and LOF, each shown on its own. Both measure how unusual the claim is against a
 * synthetic baseline. Neither measures fraud, and the page says so.
 */
export function AnomalyResults({ screening, currency }: { screening: RiskScreening; currency: string }) {
  const unusual = screening.features.filter((feature) => isUnusual(feature.baselinePercentile)).length;
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        {screening.detectors.map((result) => (
          <DetectorCard key={result.detector} result={result} />
        ))}
      </div>
      <p className="text-xs text-ink-muted">
        Statistically unusual is not the same as wrong. Both detectors learned what ordinary claims look like from{" "}
        {screening.versions.baselineSize.toLocaleString("en-IN")} synthetic claims, and flag about 1 in 100 of them. Their scores are not
        probabilities of fraud.
      </p>

      <Panel>
        <div className="flex items-center justify-between gap-3 px-5 pt-4">
          <h3 className="flex items-center gap-1.5 text-[13px] font-medium">
            <Activity aria-hidden className="size-4 text-ink-muted" />
            How this claim compares with the baseline
          </h3>
          <span className="text-xs text-ink-muted">
            {unusual} of {screening.features.length} outside the usual range
          </span>
        </div>
        <ul className="mt-2 divide-y divide-line border-t border-line">
          {screening.features.map((feature) => {
            const outlier = isUnusual(feature.baselinePercentile);
            return (
              <li key={feature.name} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1.5 px-5 py-2.5" data-testid="risk-feature">
                <div className="min-w-0">
                  <p className="text-[13px]">{RISK_FEATURE_LABELS[feature.name] ?? feature.name}</p>
                  <p className="text-xs text-ink-muted tabular-nums">
                    Baseline median {formatFeature(feature.name, feature.baselineMedian, currency)}
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-right">
                    <p className="text-[13px] font-medium whitespace-nowrap tabular-nums">
                      {formatFeature(feature.name, feature.value, currency)}
                    </p>
                    <p className={`text-xs whitespace-nowrap tabular-nums ${outlier ? "font-medium text-caution" : "text-ink-muted"}`}>
                      {feature.imputed ? "Not known; median used" : `${outlier ? "Unusual · " : ""}${Math.round(feature.baselinePercentile)}th percentile`}
                    </p>
                  </div>
                  <div className="relative h-1.5 w-20 shrink-0 rounded-full bg-subtle" aria-hidden>
                    <span
                      className={`absolute top-1/2 size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full ${outlier ? "bg-caution" : "bg-ink-faint"}`}
                      style={{ left: `${feature.baselinePercentile}%` }}
                    />
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
        <p className="border-t border-line px-5 py-3 text-xs text-ink-muted">
          Where each value sits among the baseline claims. This describes the claim. It is not how the detectors weigh the values, which
          they do in combination.
        </p>
      </Panel>

      <details className="group rounded-panel border border-line bg-surface" data-testid="technical-details">
        <summary className="flex cursor-pointer list-none items-center gap-2 px-5 py-3.5 text-[13px] font-medium">
          <FlaskConical aria-hidden className="size-4 text-ink-muted" />
          Technical details, for demonstration
        </summary>
        <div className="space-y-3 border-t border-line px-5 py-4 text-[13px]">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            {screening.detectors.map((result) => (
              <div key={result.detector} className="contents">
                <dt className="text-ink-muted">{DETECTOR_LABELS[result.detector].name}</dt>
                <dd className="min-w-0 break-words tabular-nums">
                  score {Number(result.score).toFixed(4)}, threshold {Number(result.threshold).toFixed(4)} (99th percentile of the
                  baseline), percentile {Number(result.percentile).toFixed(2)}
                </dd>
              </div>
            ))}
            <dt className="text-ink-muted">Method</dt>
            <dd>
              {DETECTOR_LABELS.ISOLATION_FOREST.method} {DETECTOR_LABELS.LOCAL_OUTLIER_FACTOR.method} Isolation Forest: 200 trees of 256
              samples. LOF: 35 neighbors. Features are log-scaled where skewed, then standardized.
            </dd>
            <dt className="text-ink-muted">Model</dt>
            <dd className="min-w-0 break-all">
              {screening.versions.model}, features {screening.versions.features}, fingerprint {screening.versions.fingerprint.slice(0, 16)}…
            </dd>
            <dt className="text-ink-muted">Training data</dt>
            <dd>
              {screening.versions.baselineSize.toLocaleString("en-IN")} synthetic claims from a seeded generator. No real claims, and no
              labels: anomaly labels exist only in the separate evaluation set.
            </dd>
            <dt className="text-ink-muted">Rules</dt>
            <dd>
              {screening.versions.ruleset}, scored with {screening.versions.scoring}
            </dd>
          </dl>
        </div>
      </details>
    </div>
  );
}
