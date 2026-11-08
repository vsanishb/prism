import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed } from "@/components/ui/layout";
import { EvidenceChainView } from "@/features/review/evidence-chain";
import { serverFetch } from "@/lib/server-api";
import type { EvidenceChain } from "@/lib/types";

export const metadata: Metadata = { title: "Evidence chain" };

export default async function EvidenceChainPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const result = await serverFetch<EvidenceChain>(`/api/v1/claims/${id}/evidence-chain`);
  if (!result.ok) {
    if (result.status === 404 || result.status === 400) {
      notFound();
    }
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  return (
    <>
      <Link href={`/claims/${id}`} className="mb-4 inline-flex items-center gap-1 text-sm text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {result.data.claimNumber}
      </Link>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">Evidence chain</h1>
        <p className="mt-1 max-w-2xl text-ink-muted">
          From the authorized outcome back to the people, the analysis and screening it was decided with, the facts, their source pages,
          the AI runs, and the documents.
        </p>
      </header>
      <EvidenceChainView claimId={id} chain={result.data} />
    </>
  );
}
