import { ChevronLeft, Scale } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { EmptyState } from "@/components/ui/feedback";
import { AccessDenied, LoadFailed, Panel } from "@/components/ui/layout";
import { PolicyAssessmentView } from "@/features/claims/policy-assessment";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, PolicyAssessment } from "@/lib/types";

export const metadata: Metadata = { title: "Policy analysis" };

export default async function ClaimAssessmentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [claim, assessment] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<{ assessment: PolicyAssessment | null }>(`/api/v1/claims/${id}/assessment`),
  ]);
  if (!claim.ok || !assessment.ok) {
    const status = claim.ok ? (assessment.ok ? 200 : assessment.status) : claim.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const { summary } = claim.data;
  const analysis = assessment.data.assessment;

  return (
    <>
      <Link href={`/claims/${id}`} className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {summary.claimNumber}
      </Link>
      <header className="mb-6">
        <h1 className="text-[22px] font-semibold tracking-tight">Policy analysis</h1>
        <p className="mt-1 max-w-2xl text-ink-muted">
          Each issue the claim raises, read against the published policy wording by AI. Citations are checked against the wording, and
          amounts and dates are calculated by Prism from values a person confirmed.
        </p>
        <p className="mt-2 max-w-2xl text-[13px] font-medium" role="note">
          This analysis assists review. It is not a claim decision.
        </p>
      </header>
      {analysis === null ? (
        <Panel>
          <EmptyState icon={Scale} title="No policy analysis yet">
            It appears once the claim&apos;s documents are processed, if its policy version is published and searchable.
          </EmptyState>
        </Panel>
      ) : (
        <PolicyAssessmentView assessment={analysis} currency={summary.currency} />
      )}
    </>
  );
}
