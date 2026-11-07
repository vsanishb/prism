import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Badge } from "@/components/ui/feedback";
import { AccessDenied, LoadFailed } from "@/components/ui/layout";
import { PolicyKnowledgeReview } from "@/features/policies/policy-knowledge";
import { SearchIndexStatus } from "@/features/policies/search-index-status";
import { formatMoney } from "@/lib/format";
import { KNOWLEDGE_STATUS_LABELS } from "@/lib/labels";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { PolicyDetail, PolicyKnowledge, SearchIndex } from "@/lib/types";

export const metadata: Metadata = { title: "Policy knowledge" };

const TONES = { NO_WORDING: "neutral", PROCESSING: "accent", IN_REVIEW: "caution", PUBLISHED: "positive", REJECTED: "critical" } as const;

export default async function PolicyVersionPage({ params }: { params: Promise<{ id: string; versionId: string }> }) {
  const { id, versionId } = await params;
  const [policy, knowledge, index, user] = await Promise.all([
    serverFetch<PolicyDetail>(`/api/v1/policies/${id}`),
    serverFetch<PolicyKnowledge>(`/api/v1/policies/${id}/versions/${versionId}/knowledge`),
    serverFetch<SearchIndex>(`/api/v1/policies/${id}/versions/${versionId}/search-index`),
    requireCurrentUser(),
  ]);
  if (!policy.ok || !knowledge.ok) {
    const status = !policy.ok ? policy.status : knowledge.ok ? 200 : knowledge.status;
    if (status === 404 || status === 400) {
      notFound();
    }
    return status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const data = knowledge.data;
  const canManage = Boolean(user?.permissions.includes("POLICY_MANAGE"));

  return (
    <>
      <Link href={`/policies/${id}`} className="mb-4 inline-flex items-center gap-1 text-[13px] text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        {policy.data.policy.policyNumber}
      </Link>
      <header className="mb-8">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-[22px] font-semibold tracking-tight">Version {data.versionNumber}</h1>
          <Badge tone={TONES[data.knowledgeStatus]}>{KNOWLEDGE_STATUS_LABELS[data.knowledgeStatus]}</Badge>
        </div>
        <p className="mt-1 max-w-2xl text-ink-muted">
          {policy.data.policy.productName} · Sum insured {formatMoney(data.sumInsured, data.currency)}
          {data.documentName ? ` · ${data.documentName}, uploaded by ${data.uploadedByName ?? "a policy manager"}` : ""}
        </p>
        <p className="mt-2 max-w-2xl text-[13px] text-ink-muted">
          Read from the wording by AI and checked by rules. The wording remains the authority: confirm each critical value against its page
          before publishing.
        </p>
      </header>
      {data.knowledgeStatus === "PUBLISHED" ? (
        index.ok ? (
          <SearchIndexStatus policyId={id} versionId={versionId} index={index.data} canManage={canManage} />
        ) : (
          <p className="mb-6 text-[13px] text-ink-muted">Search status could not be loaded. Refresh to try again.</p>
        )
      ) : null}
      <PolicyKnowledgeReview policyId={id} knowledge={data} canManage={canManage} />
    </>
  );
}
