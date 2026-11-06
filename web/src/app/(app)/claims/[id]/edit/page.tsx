import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { LoadFailed, PageHeader } from "@/components/ui/layout";
import { ClaimForm } from "@/features/claims/claim-form";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, Page, PolicySummary } from "@/lib/types";

export const metadata: Metadata = { title: "Edit claim" };

export default async function EditClaimPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [claim, policies] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<Page<PolicySummary>>("/api/v1/policies?limit=100"),
  ]);
  if (!claim.ok && claim.status === 404) {
    notFound();
  }
  if (!claim.ok || !policies.ok) {
    return <LoadFailed />;
  }
  return (
    <>
      <PageHeader title={`Edit ${claim.data.summary.claimNumber}`} />
      <ClaimForm policies={policies.data.items} claim={claim.data} />
    </>
  );
}
