import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { SystemChecks } from "@/features/system/system-checks";
import { serverFetch } from "@/lib/server-api";
import type { SystemStatus } from "@/lib/types";

export const metadata: Metadata = { title: "System status" };

export default async function SystemPage() {
  const result = await serverFetch<SystemStatus>("/api/v1/system/status");
  return (
    <>
      <PageHeader title="System status" description="Live checks of Prism's services and the trust between them." />
      {result.ok ? <SystemChecks status={result.data} /> : result.status === 403 ? <AccessDenied /> : <LoadFailed />}
    </>
  );
}
