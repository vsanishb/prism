import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { AuditExplorer } from "@/features/audit/audit-explorer";
import { VerifyChainButton } from "@/features/audit/verify-chain";
import { serverFetch } from "@/lib/server-api";
import type { AuditEvent, Page } from "@/lib/types";

export const metadata: Metadata = { title: "Audit trail" };

interface SearchParams {
  action?: string;
  outcome?: string;
  cursor?: string;
}

export default async function AuditPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { action, outcome, cursor } = await searchParams;
  const query = new URLSearchParams({ limit: "25" });
  if (action) query.set("action", action);
  if (outcome) query.set("outcome", outcome);
  if (cursor) query.set("cursor", cursor);

  const result = await serverFetch<Page<AuditEvent>>(`/api/v1/audit/events?${query}`);
  if (!result.ok && result.status === 403) {
    return (
      <>
        <PageHeader title="Audit trail" />
        <AccessDenied />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="An append-only record of important actions. Each event is chained to the one before it, so changes can be detected."
      />
      <VerifyChainButton />
      {result.ok ? <AuditExplorer page={result.data} action={action ?? ""} outcome={outcome ?? ""} paged={Boolean(cursor)} /> : <LoadFailed />}
    </>
  );
}
