import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed } from "@/components/ui/layout";
import { RetrievalExplorer } from "@/features/policies/retrieval-explorer";
import { SearchIndexStatus } from "@/features/policies/search-index-status";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { PolicyDetail, PolicyKnowledge, SearchIndex } from "@/lib/types";

export const metadata: Metadata = { title: "Search policy" };

export default async function PolicySearchPage({ params }: { params: Promise<{ id: string; versionId: string }> }) {
  const { id, versionId } = await params;
  const [policy, knowledge, index, user] = await Promise.all([
    serverFetch<PolicyDetail>(`/api/v1/policies/${id}`),
    serverFetch<PolicyKnowledge>(`/api/v1/policies/${id}/versions/${versionId}/knowledge`),
    serverFetch<SearchIndex>(`/api/v1/policies/${id}/versions/${versionId}/search-index`),
    requireCurrentUser(),
  ]);
  if (!policy.ok || !knowledge.ok || !index.ok) {
    const status = !policy.ok ? policy.status : !knowledge.ok ? knowledge.status : index.ok ? 200 : index.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const versionPath = `/policies/${id}/versions/${versionId}`;

  return (
    <>
      <Link href={versionPath} className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {policy.data.policy.policyNumber} · Version {knowledge.data.versionNumber}
      </Link>
      <header className="mb-8">
        <h1 className="text-[22px] font-semibold tracking-tight">Search policy wording</h1>
        <p className="mt-1 max-w-2xl text-ink-muted">
          {policy.data.policy.productName}, version {knowledge.data.versionNumber}. Results are passages of the published wording, found
          by meaning and by words, and linked to the exceptions and definitions they depend on. The wording remains the authority.
        </p>
      </header>
      {index.data.status === "READY" && knowledge.data.documentId ? (
        <RetrievalExplorer policyId={id} versionId={versionId} documentId={knowledge.data.documentId} />
      ) : (
        <SearchIndexStatus
          policyId={id}
          versionId={versionId}
          index={index.data}
          canManage={Boolean(user?.permissions.includes("POLICY_MANAGE"))}
        />
      )}
    </>
  );
}
