import { ChevronLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { PolicyVersions } from "@/features/policies/policy-versions";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { PolicyDetail } from "@/lib/types";

export const metadata: Metadata = { title: "Policy" };

export default async function PolicyPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const [result, user] = await Promise.all([serverFetch<PolicyDetail>(`/api/v1/policies/${id}`), requireCurrentUser()]);
  if (!result.ok) {
    if (result.status === 404 || result.status === 400) {
      notFound();
    }
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const { policy, versions } = result.data;
  return (
    <>
      <Link href="/policies" className="mb-4 inline-flex items-center gap-1 text-sm text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        Policies
      </Link>
      <PageHeader title={policy.policyNumber} description={`${policy.productName} · ${policy.policyholderName}`} />
      <PolicyVersions policyId={policy.id} versions={versions} canManage={Boolean(user?.permissions.includes("POLICY_MANAGE"))} />
    </>
  );
}
