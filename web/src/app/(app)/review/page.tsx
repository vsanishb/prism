import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { ReviewDashboard } from "@/features/review/review-dashboard";
import { serverFetch } from "@/lib/server-api";
import type { TaskLists } from "@/lib/types";

export const metadata: Metadata = { title: "Review" };

export default async function ReviewPage() {
  const result = await serverFetch<TaskLists>("/api/v1/review/tasks");
  if (!result.ok) {
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  return (
    <>
      <PageHeader
        title="Review"
        description="Every claim reaches a person. Tasks are routed by risk and amount: high-risk claims go to an investigator first, and claims above the reviewer authority limit to senior reviewers."
      />
      <ReviewDashboard lists={result.data} />
    </>
  );
}
