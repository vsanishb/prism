import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed, Panel, SectionTitle } from "@/components/ui/layout";
import { ClaimActions } from "@/features/claims/claim-actions";
import { ClaimStatusBadge } from "@/features/claims/claim-status";
import { ClaimTimeline } from "@/features/claims/claim-timeline";
import { DocumentPanel } from "@/features/claims/document-panel";
import { SubmissionChecklist } from "@/features/claims/submission-checklist";
import { formatDate, formatDateTime, formatMoney } from "@/lib/format";
import { CLAIM_TYPE_LABELS, WITHDRAWAL_REASON_LABELS } from "@/lib/labels";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, TimelineEntry } from "@/lib/types";

export const metadata: Metadata = { title: "Claim" };

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="mt-0.5 text-[13px]">{children}</dd>
    </div>
  );
}

export default async function ClaimPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [result, timeline] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<TimelineEntry[]>(`/api/v1/claims/${id}/timeline`),
  ]);
  if (!result.ok) {
    if (result.status === 404 || result.status === 400) {
      notFound();
    }
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const claim = result.data;
  const { summary } = claim;
  const version = claim.policyVersion;

  return (
    <>
      <Link href="/claims" className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        Claims
      </Link>
      <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-[22px] font-semibold tracking-tight">{summary.claimNumber}</h1>
            <ClaimStatusBadge status={summary.status} />
          </div>
          <p className="mt-1 text-ink-muted">
            {summary.patientName} · {formatMoney(summary.claimedAmount, summary.currency)}
          </p>
        </div>
        <ClaimActions claim={claim} />
      </header>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="min-w-0 space-y-8">
          <div>
            <SectionTitle>Claim details</SectionTitle>
            <Panel className="p-5">
              <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">
                <Fact label="Claimant">{claim.claimantName}</Fact>
                <Fact label="Patient">{summary.patientName}</Fact>
                <Fact label="Hospital">{summary.hospitalName}</Fact>
                <Fact label="Claim type">{CLAIM_TYPE_LABELS[summary.type]}</Fact>
                <Fact label="Admission">{formatDate(summary.admissionDate)}</Fact>
                <Fact label="Discharge">{formatDate(claim.dischargeDate)}</Fact>
                <Fact label="Claimed amount">{formatMoney(summary.claimedAmount, summary.currency)}</Fact>
                {claim.submittedAt ? <Fact label="Submitted">{formatDateTime(claim.submittedAt)}</Fact> : null}
                {claim.withdrawalReason ? <Fact label="Withdrawal reason">{WITHDRAWAL_REASON_LABELS[claim.withdrawalReason]}</Fact> : null}
              </dl>
            </Panel>
          </div>

          <div>
            <SectionTitle>Policy</SectionTitle>
            <Panel className="p-5">
              <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">
                <Fact label="Policy">
                  <Link href={`/policies/${claim.policy.id}`} className="font-medium hover:text-accent">
                    {claim.policy.policyNumber}
                  </Link>
                </Fact>
                <Fact label="Product">{claim.policy.productName}</Fact>
                <Fact label={claim.policyVersionFixed ? "Applicable version" : "Version for this admission date"}>
                  {version
                    ? `Version ${version.versionNumber}: ${formatDate(version.effectiveFrom)} to ${formatDate(version.effectiveTo)}`
                    : "No version covers this date"}
                </Fact>
                {version ? <Fact label="Sum insured">{formatMoney(version.sumInsured, version.currency)}</Fact> : null}
              </dl>
            </Panel>
          </div>

          <DocumentPanel claim={claim} />
        </div>

        <aside className="space-y-8">
          {summary.status === "DRAFT" ? <SubmissionChecklist issues={claim.submissionIssues} /> : null}
          <div>
            <SectionTitle>Activity</SectionTitle>
            {timeline.ok ? <ClaimTimeline entries={timeline.data} /> : <p className="text-[13px] text-ink-muted">Activity could not be loaded.</p>}
          </div>
        </aside>
      </div>
    </>
  );
}
