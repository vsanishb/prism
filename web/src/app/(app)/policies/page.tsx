import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { PolicyRegister } from "@/features/policies/policy-register";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { Page, PolicySummary } from "@/lib/types";

export const metadata: Metadata = { title: "Policies" };

export default async function PoliciesPage({ searchParams }: { searchParams: Promise<{ search?: string; cursor?: string }> }) {
  const { search, cursor } = await searchParams;
  const query = new URLSearchParams({ limit: "25" });
  if (search) query.set("search", search);
  if (cursor) query.set("cursor", cursor);
  const [result, user] = await Promise.all([serverFetch<Page<PolicySummary>>(`/api/v1/policies?${query}`), requireCurrentUser()]);
  if (!result.ok) {
    return (
      <>
        <PageHeader title="Policies" />
        {result.status === 403 ? <AccessDenied /> : <LoadFailed />}
      </>
    );
  }
  return (
    <PolicyRegister
      page={result.data}
      search={search ?? ""}
      paged={Boolean(cursor)}
      canManage={Boolean(user?.permissions.includes("POLICY_MANAGE"))}
    />
  );
}
