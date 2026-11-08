"use client";

import { CircleAlert, CircleCheck, CircleDashed, FileSearch, LoaderCircle, RotateCw, Scale, ShieldAlert, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { DOCUMENT_TYPE_LABELS, PROCESSING_FAILURE_MESSAGES, REVIEW_ROUTE_LABELS, RISK_BAND_LABELS, STAGE_LABELS } from "@/lib/labels";
import type { ClaimFacts, DocumentRecord, ProcessingView, RiskBand, StageStatus, StageView } from "@/lib/types";

const POLL_MILLIS = 2500;

const STATUS_ICONS = {
  NOT_STARTED: { icon: CircleDashed, tone: "text-ink-faint", label: "Not started" },
  PROCESSING: { icon: LoaderCircle, tone: "animate-spin text-accent", label: "In progress" },
  COMPLETED: { icon: CircleCheck, tone: "text-positive", label: "Done" },
  REQUIRES_REVIEW: { icon: TriangleAlert, tone: "text-caution", label: "Needs a look" },
  FAILED: { icon: CircleAlert, tone: "text-critical", label: "Failed" },
} as const;

function Step({ label, status, detail }: { label: string; status: StageStatus; detail?: string | undefined }) {
  const { icon: Icon, tone, label: statusLabel } = STATUS_ICONS[status];
  return (
    <li className="flex items-start gap-3 px-5 py-3">
      <Icon aria-hidden className={`mt-0.5 size-4 shrink-0 ${tone}`} />
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium break-words">{label}</p>
        {detail ? <p className="mt-0.5 text-xs text-ink-muted">{detail}</p> : null}
      </div>
      <span className="shrink-0 text-xs text-ink-muted">{statusLabel}</span>
    </li>
  );
}

function stageStatus(stages: StageView[], stage: StageView["stage"]): StageView | undefined {
  return stages.find((candidate) => candidate.stage === stage);
}

/** A screened claim's band is the stage's detail. It changes how closely the claim is reviewed, nothing more. */
function riskDetail(stage: StageView | undefined): string | undefined {
  if (stage?.status === "COMPLETED" || stage?.status === "REQUIRES_REVIEW") {
    const band = stage.detailCode as RiskBand | null;
    return band && band in RISK_BAND_LABELS
      ? `${RISK_BAND_LABELS[band]}. ${REVIEW_ROUTE_LABELS[band === "HIGH" ? "SENIOR_REVIEW" : "STANDARD_REVIEW"]}.`
      : undefined;
  }
  return failureDetail(stage);
}

function failureDetail(stage: StageView | undefined): string | undefined {
  if (stage?.status !== "FAILED") {
    return undefined;
  }
  return PROCESSING_FAILURE_MESSAGES[stage.detailCode ?? ""] ?? PROCESSING_FAILURE_MESSAGES.default;
}

/** Status comes from Postgres through the API, so this keeps working during a workflow outage. */
export function ProcessingPanel({
  claimId,
  initial,
  documents,
  facts,
  canRetry,
}: {
  claimId: string;
  initial: ProcessingView;
  documents: DocumentRecord[];
  facts: ClaimFacts | null;
  canRetry: boolean;
}) {
  const router = useRouter();
  const [view, setView] = useState(initial);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const seen = useRef(JSON.stringify(initial));

  useEffect(() => {
    const timer = setInterval(() => {
      void (async () => {
        if (document.hidden) {
          return;
        }
        try {
          const next = await apiRequest<ProcessingView>("GET", `/api/v1/claims/${claimId}/processing`);
          const fingerprint = JSON.stringify(next);
          if (fingerprint !== seen.current) {
            seen.current = fingerprint;
            setView(next);
            // The rest of the page, such as the status badge and activity, changes with it.
            router.refresh();
          }
        } catch {
          // A missed poll changes nothing on screen. The next one tries again.
        }
      })();
    }, POLL_MILLIS);
    return () => clearInterval(timer);
  }, [claimId, router]);

  async function retry() {
    setRetrying(true);
    setError(null);
    try {
      await apiRequest("POST", `/api/v1/claims/${claimId}/processing/retry`);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setRetrying(false);
    }
  }

  const active = documents.filter((document) => document.status === "ACTIVE");
  const consolidation = stageStatus(view.stages, "CONSOLIDATION");
  const validation = stageStatus(view.stages, "VALIDATION");
  const analysis = stageStatus(view.stages, "POLICY_ANALYSIS");
  const analyzed = analysis?.status === "COMPLETED" || analysis?.status === "REQUIRES_REVIEW";
  const risk = stageStatus(view.stages, "RISK_SCREENING");
  const screened = risk?.status === "COMPLETED" || risk?.status === "REQUIRES_REVIEW";
  const missing = (facts?.findings ?? []).filter((finding) => finding.code === "MISSING_REQUIRED_DOCUMENT");
  const waiting = view.claimStatus === "AWAITING_INFORMATION";

  return (
    <div>
      <SectionTitle>Processing</SectionTitle>
      <Panel>
        {waiting && missing.length > 0 ? (
          <div className="border-b border-line p-5">
            <div role="status" className="rounded-control bg-caution-soft px-3 py-2.5 text-[13px]">
              <p className="font-medium text-caution">Waiting for documents</p>
              <p className="mt-0.5 text-ink-muted">
                Add{" "}
                {missing
                  .map((finding) => DOCUMENT_TYPE_LABELS[finding.expectedValue as keyof typeof DOCUMENT_TYPE_LABELS] ?? finding.expectedValue)
                  .join(", ")
                  .toLowerCase()}
                . Processing continues on its own once they arrive.
              </p>
            </div>
          </div>
        ) : null}
        <ol className="divide-y divide-line">
          {active.map((document) => {
            const stage = view.stages.find((candidate) => candidate.documentId === document.id);
            return (
              <Step
                key={document.id}
                label={document.originalFilename}
                status={stage?.status ?? "NOT_STARTED"}
                detail={failureDetail(stage)}
              />
            );
          })}
          <Step label={STAGE_LABELS.CONSOLIDATION} status={consolidation?.status ?? "NOT_STARTED"} detail={failureDetail(consolidation)} />
          <Step label={STAGE_LABELS.VALIDATION} status={validation?.status ?? "NOT_STARTED"} />
          <Step label={STAGE_LABELS.POLICY_ANALYSIS} status={analysis?.status ?? "NOT_STARTED"} detail={failureDetail(analysis)} />
          <Step label={STAGE_LABELS.RISK_SCREENING} status={risk?.status ?? "NOT_STARTED"} detail={riskDetail(risk)} />
        </ol>
        {error ? (
          <div className="border-t border-line p-5">
            <Notice tone="critical" title={error} />
          </div>
        ) : null}
        {view.retryAvailable || facts?.sequence ? (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line px-5 py-3.5">
            {facts?.sequence ? (
              <div className="flex flex-wrap gap-x-5 gap-y-2">
                <Link href={`/claims/${claimId}/facts`} className="inline-flex items-center gap-1.5 text-[13px] font-medium text-accent hover:text-accent-hover">
                  <FileSearch aria-hidden className="size-4" />
                  Review extracted facts
                </Link>
                {analyzed ? (
                  <Link
                    href={`/claims/${claimId}/assessment`}
                    className="inline-flex items-center gap-1.5 text-[13px] font-medium text-accent hover:text-accent-hover"
                  >
                    <Scale aria-hidden className="size-4" />
                    Read policy analysis
                  </Link>
                ) : null}
                {screened ? (
                  <Link
                    href={`/claims/${claimId}/risk`}
                    className="inline-flex items-center gap-1.5 text-[13px] font-medium text-accent hover:text-accent-hover"
                  >
                    <ShieldAlert aria-hidden className="size-4" />
                    Read risk screening
                  </Link>
                ) : null}
              </div>
            ) : (
              <span />
            )}
            {view.retryAvailable && canRetry ? (
              <Button variant="secondary" onClick={retry} disabled={retrying}>
                <RotateCw aria-hidden className="size-4" />
                Retry
              </Button>
            ) : null}
          </div>
        ) : null}
      </Panel>
    </div>
  );
}
