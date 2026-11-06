import { Plus } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { ClaimsTable } from "@/features/claims/claims-table";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { ClaimSummary, Page } from "@/lib/types";

export const metadata: Metadata = { title: "Claims" };

interface SearchParams {
  status?: string;
  search?: string;
  cursor?: string;
}

export default async function ClaimsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const { status, search, cursor } = await searchParams;
  const query = new URLSearchParams({ limit: "25" });
  if (status) query.set("status", status);
  if (search) query.set("search", search);
  if (cursor) query.set("cursor", cursor);

  const [result, user] = await Promise.all([serverFetch<Page<ClaimSummary>>(`/api/v1/claims?${query}`), requireCurrentUser()]);
  if (!result.ok && result.status === 403) {
    return (
      <>
        <PageHeader title="Claims" />
        <AccessDenied />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Claims"
        description="Health insurance claims and their documents."
        actions={
          user?.permissions.includes("CLAIM_MANAGE") ? (
            <Link
              href="/claims/new"
              className="inline-flex h-9 items-center gap-2 rounded-control bg-accent px-3.5 text-[13px] font-medium text-white transition-colors hover:bg-accent-hover"
            >
              <Plus aria-hidden className="size-4" />
              New claim
            </Link>
          ) : undefined
        }
      />
      {result.ok ? <ClaimsTable page={result.data} status={status ?? ""} search={search ?? ""} paged={Boolean(cursor)} /> : <LoadFailed />}
    </>
  );
}
