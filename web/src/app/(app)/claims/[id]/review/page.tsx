import { ChevronLeft, Route } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed } from "@/components/ui/layout";
import { ClaimStatusBadge } from "@/features/claims/claim-status";
import { ReviewWorkspaceView } from "@/features/review/review-workspace";
import { formatMoney } from "@/lib/format";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, ClaimFacts, PolicyAssessment, ReviewWorkspace, RiskScreening } from "@/lib/types";

export const metadata: Metadata = { title: "Review workspace" };

export default async function ReviewWorkspacePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [claim, review, facts, assessment, risk] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<ReviewWorkspace>(`/api/v1/claims/${id}/review`),
    serverFetch<ClaimFacts>(`/api/v1/claims/${id}/facts`),
    serverFetch<{ assessment: PolicyAssessment | null }>(`/api/v1/claims/${id}/assessment`),
    serverFetch<{ screening: RiskScreening | null }>(`/api/v1/claims/${id}/risk`),
  ]);
  if (!claim.ok || !review.ok) {
    const status = claim.ok ? (review.ok ? 200 : review.status) : claim.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const { summary } = claim.data;

  return (
    <>
      <Link href={`/claims/${id}`} className="mb-4 inline-flex items-center gap-1 text-sm text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {summary.claimNumber}
      </Link>
      <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight">Review {summary.claimNumber}</h1>
            <ClaimStatusBadge status={summary.status} />
          </div>
          <p className="mt-1 text-ink-muted">
            {summary.patientName} · {summary.hospitalName} · {formatMoney(summary.claimedAmount, summary.currency)}
          </p>
          <p className="mt-2 max-w-2xl text-sm font-medium" role="note">
            You decide. The analysis and screening are evidence for your decision, not decisions.
          </p>
        </div>
        <Link
          href={`/claims/${id}/evidence`}
          className="inline-flex h-9 items-center gap-1.5 rounded-control border border-line-strong bg-surface px-3.5 text-sm font-medium hover:bg-subtle"
        >
          <Route aria-hidden className="size-4" />
          Evidence chain
        </Link>
      </header>
      <ReviewWorkspaceView
        claim={claim.data}
        facts={facts.ok ? facts.data : null}
        assessment={assessment.ok ? assessment.data.assessment : null}
        screening={risk.ok ? risk.data.screening : null}
        review={review.data}
      />
    </>
  );
}
