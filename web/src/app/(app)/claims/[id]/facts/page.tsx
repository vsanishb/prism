import { ChevronLeft, FileSearch } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { EmptyState } from "@/components/ui/feedback";
import { AccessDenied, LoadFailed, Panel } from "@/components/ui/layout";
import { FactReview } from "@/features/claims/fact-review";
import { formatDateTime } from "@/lib/format";
import { serverFetch } from "@/lib/server-api";
import type { ClaimDetail, ClaimFacts } from "@/lib/types";

export const metadata: Metadata = { title: "Extracted facts" };

export default async function ClaimFactsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [claim, facts] = await Promise.all([
    serverFetch<ClaimDetail>(`/api/v1/claims/${id}`),
    serverFetch<ClaimFacts>(`/api/v1/claims/${id}/facts`),
  ]);
  if (!claim.ok || !facts.ok) {
    const status = claim.ok ? (facts.ok ? 200 : facts.status) : claim.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const { summary } = claim.data;

  return (
    <>
      <Link href={`/claims/${id}`} className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {summary.claimNumber}
      </Link>
      <header className="mb-8">
        <h1 className="text-[22px] font-semibold tracking-tight">Extracted facts</h1>
        <p className="mt-1 max-w-2xl text-ink-muted">
          Read from the documents by AI and checked by rules. The source documents remain the authority, so open a fact to see where it came
          from.
          {facts.data.consolidatedAt ? ` Updated ${formatDateTime(facts.data.consolidatedAt)}.` : ""}
        </p>
      </header>
      {facts.data.sequence === null ? (
        <Panel>
          <EmptyState icon={FileSearch} title="Nothing extracted yet">
            Facts appear here once the claim&apos;s documents have been processed.
          </EmptyState>
        </Panel>
      ) : (
        <FactReview facts={facts.data} currency={summary.currency} />
      )}
    </>
  );
}
