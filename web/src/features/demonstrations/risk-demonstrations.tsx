"use client";

import { CircleCheck, ExternalLink, LoaderCircle, Play, ShieldAlert } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge, Notice } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { CLAIM_STATUS_LABELS, RISK_BAND_LABELS } from "@/lib/labels";
import type { DemonstrationScenario, RiskBand } from "@/lib/types";
import { BAND_TONES } from "@/features/risk/risk-format";
import { SCENARIOS } from "./scenarios";

const POLL_MILLIS = 4000;
const PROCESSING = new Set(["SUBMITTED", "PROCESSING", "AWAITING_INFORMATION"]);

function ScenarioCard({ scenario, band, index }: { scenario: DemonstrationScenario; band: RiskBand | null; index: number }) {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const description = SCENARIOS[scenario.scenario];
  const { claim } = scenario;
  const draft = claim.status === "DRAFT";

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await apiRequest("POST", `/api/v1/claims/${claim.id}/submission`);
      router.push(`/claims/${claim.id}`);
    } catch (failure) {
      setError(errorMessage(failure));
      setSubmitting(false);
    }
  }

  return (
    <li>
      <Panel className="flex h-full flex-col p-5">
        <div data-testid={`scenario-${scenario.scenario}`} className="flex h-full flex-col">
          <p className="text-xs font-medium text-ink-muted">Scenario {index + 1}</p>
          <h2 className="mt-0.5 text-lg font-semibold tracking-tight">{description.title}</h2>
          <p className="text-sm text-ink-muted">{description.summary}</p>
          <p className="mt-3 text-sm">{description.story}</p>

          <p className="mt-4 mb-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">Embedded signals</p>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {description.signals.map((signal) => (
              <li key={signal}>{signal}</li>
            ))}
          </ul>
          <p className="mt-4 mb-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">What to expect</p>
          <p className="text-sm">{description.expected}</p>

          <div className="mt-auto pt-5">
            {error ? (
              <div className="mb-3">
                <Notice tone="critical" title={error} />
              </div>
            ) : null}
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
              <div className="text-sm">
                <p className="font-medium">{claim.claimNumber}</p>
                <p className="text-xs text-ink-muted">{draft ? "Draft, ready to submit" : CLAIM_STATUS_LABELS[claim.status]}</p>
              </div>
              {draft ? (
                <Button onClick={submit} loading={submitting} disabled={!scenario.ready}>
                  <Play aria-hidden className="size-4" />
                  Submit scenario claim
                </Button>
              ) : (
                <div className="flex flex-wrap items-center gap-3">
                  {band ? (
                    <Badge tone={BAND_TONES[band]}>
                      <span data-testid="scenario-band">{RISK_BAND_LABELS[band]}</span>
                    </Badge>
                  ) : PROCESSING.has(claim.status) ? (
                    <span className="inline-flex items-center gap-1.5 text-xs text-ink-muted">
                      <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
                      Processing
                    </span>
                  ) : null}
                  {band ? (
                    <Link
                      href={`/claims/${claim.id}/risk`}
                      className="inline-flex items-center gap-1.5 text-sm font-medium text-accent hover:text-accent-hover"
                    >
                      <ShieldAlert aria-hidden className="size-4" />
                      Read risk screening
                    </Link>
                  ) : (
                    <Link href={`/claims/${claim.id}`} className="text-sm font-medium text-accent hover:text-accent-hover">
                      Open claim
                    </Link>
                  )}
                </div>
              )}
            </div>
            {draft && !scenario.ready ? (
              <p className="mt-2 text-xs text-ink-muted">Waits until the earlier claims it is compared with are processed.</p>
            ) : null}
          </div>
        </div>
      </Panel>
    </li>
  );
}

/** The seeded scenarios. Submitting one runs the normal pipeline: nothing about the claim is special. */
export function RiskDemonstrations({ scenarios, bands }: { scenarios: DemonstrationScenario[]; bands: Record<string, RiskBand> }) {
  const router = useRouter();
  const earlier = scenarios.flatMap((scenario) => scenario.earlierClaims);
  const waiting = scenarios.some(
    (scenario) => !scenario.ready || (scenario.claim.status !== "DRAFT" && !bands[scenario.claim.id] && PROCESSING.has(scenario.claim.status)),
  );

  useEffect(() => {
    if (!waiting) {
      return;
    }
    const timer = setInterval(() => {
      if (!document.hidden) {
        router.refresh();
      }
    }, POLL_MILLIS);
    return () => clearInterval(timer);
  }, [waiting, router]);

  return (
    <div className="space-y-6">
      <Panel className="p-5">
        <h2 className="text-sm font-medium">Earlier claims these scenarios are compared with</h2>
        <p className="mt-0.5 text-xs text-ink-muted">
          Seeded and submitted with the demonstration data, so that duplicate checks have a history to search. They are processed when the
          core service starts.
        </p>
        <ul className="mt-3 flex flex-wrap gap-2">
          {earlier.map((claim) => {
            const done = !PROCESSING.has(claim.status);
            return (
              <li key={claim.id}>
                <Link
                  href={`/claims/${claim.id}`}
                  className="inline-flex items-center gap-1.5 rounded-control border border-line px-2.5 py-1.5 text-sm hover:bg-subtle"
                  data-testid="earlier-claim"
                >
                  {done ? (
                    <CircleCheck aria-hidden className="size-4 text-positive" />
                  ) : (
                    <LoaderCircle aria-hidden className="size-4 animate-spin text-accent" />
                  )}
                  {claim.claimNumber}
                  <span className="text-xs text-ink-muted">{CLAIM_STATUS_LABELS[claim.status]}</span>
                  <ExternalLink aria-hidden className="size-3.5 text-ink-faint" />
                </Link>
              </li>
            );
          })}
        </ul>
      </Panel>
      <ol className="grid gap-4 lg:grid-cols-3">
        {scenarios.map((scenario, index) => (
          <ScenarioCard key={scenario.scenario} scenario={scenario} band={bands[scenario.claim.id] ?? null} index={index} />
        ))}
      </ol>
    </div>
  );
}
