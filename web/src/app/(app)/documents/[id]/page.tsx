import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { AccessDenied, LoadFailed } from "@/components/ui/layout";
import { DocumentViewer } from "@/features/documents/document-viewer";
import { serverFetch } from "@/lib/server-api";
import type { DocumentRecord } from "@/lib/types";

export const metadata: Metadata = { title: "Document" };

/** Only same-site paths are honoured, so that a crafted link cannot send people elsewhere. */
function safeBackPath(back: string | undefined): string {
  return back && back.startsWith("/") && !back.startsWith("//") && !back.includes("\\") ? back : "/overview";
}

export default async function DocumentPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ back?: string }>;
}) {
  const [{ id }, { back }] = await Promise.all([params, searchParams]);
  const result = await serverFetch<DocumentRecord>(`/api/v1/documents/${id}`);
  if (!result.ok) {
    if (result.status === 404 || result.status === 400) {
      notFound();
    }
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  return <DocumentViewer document={result.data} backHref={safeBackPath(back)} />;
}
