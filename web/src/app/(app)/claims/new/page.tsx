import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { ClaimForm } from "@/features/claims/claim-form";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { Page, PolicySummary } from "@/lib/types";

export const metadata: Metadata = { title: "New claim" };

export default async function NewClaimPage() {
  const [user, policies] = await Promise.all([requireCurrentUser(), serverFetch<Page<PolicySummary>>("/api/v1/policies?limit=100")]);
  if (user && !user.permissions.includes("CLAIM_MANAGE")) {
    return <AccessDenied />;
  }
  return (
    <>
      <PageHeader title="New claim" description="Add the claim's details. You can upload its documents on the next page." />
      {policies.ok ? <ClaimForm policies={policies.data.items} /> : <LoadFailed />}
    </>
  );
}
