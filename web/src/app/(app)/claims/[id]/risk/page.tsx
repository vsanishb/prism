import { ChevronLeft, ShieldAlert } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { EmptyState } from "@/components/ui/feedback";
import { AccessDenied, LoadFailed, Panel } from "@/components/ui/layout";
import { RiskScreeningView } from "@/features/risk/risk-screening";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, RiskScreening } from "@/lib/types";

export const metadata: Metadata = { title: "Risk screening" };

export default async function ClaimRiskPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [claim, risk] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<{ screening: RiskScreening | null }>(`/api/v1/claims/${id}/risk`),
  ]);
  if (!claim.ok || !risk.ok) {
    const status = claim.ok ? (risk.ok ? 200 : risk.status) : claim.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const { summary } = claim.data;
  const screening = risk.data.screening;

  return (
    <>
      <Link href={`/claims/${id}`} className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {summary.claimNumber}
      </Link>
      <header className="mb-6">
        <h1 className="text-[22px] font-semibold tracking-tight">Risk screening</h1>
        <p className="mt-1 max-w-2xl text-ink-muted">
          Inconsistencies and risk indicators found by deterministic rules, and statistical anomalies found by two detectors, combined into
          a band that sets how closely this claim is reviewed.
        </p>
        <p className="mt-2 max-w-2xl text-[13px] font-medium" role="note">
          This is screening, not a finding of fraud. It never rejects a claim. Only an investigator can record confirmed fraud.
        </p>
      </header>
      {screening === null ? (
        <Panel>
          <EmptyState icon={ShieldAlert} title="No risk screening yet">
            It appears once the claim&apos;s documents are processed.
          </EmptyState>
        </Panel>
      ) : (
        <RiskScreeningView screening={screening} currency={summary.currency} />
      )}
    </>
  );
}
