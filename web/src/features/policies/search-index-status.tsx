"use client";

import { CircleAlert, CircleCheck, FileSearch, LoaderCircle, RotateCw, Search } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime } from "@/lib/format";
import { SEARCH_INDEX_LABELS } from "@/lib/labels";
import type { SearchIndex } from "@/lib/types";

const POLL_MILLIS = 3000;

/** Whether a published version can be searched yet, with a retry for a failed index. */
export function SearchIndexStatus({
  policyId,
  versionId,
  index,
  canManage,
}: {
  policyId: string;
  versionId: string;
  index: SearchIndex;
  canManage: boolean;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const waiting = index.status === "QUEUED" || index.status === "INDEXING";

  useEffect(() => {
    if (!waiting) {
      return;
    }
    const timer = setInterval(() => {
      if (!document.hidden) {
        router.refresh();
      }
    }, POLL_MILLIS);
    return () => clearInterval(timer);
  }, [waiting, router]);

  async function retry() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest("POST", `/api/v1/policies/${policyId}/versions/${versionId}/search-index/retry`);
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel className="mb-6 p-5">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex min-w-0 items-start gap-2.5">
          {index.status === "READY" ? (
            <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-positive" />
          ) : index.status === "FAILED" ? (
            <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-critical" />
          ) : index.status === "NOT_PUBLISHED" ? (
            <FileSearch aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-muted" />
          ) : (
            <LoaderCircle aria-hidden className="mt-0.5 size-4 shrink-0 animate-spin text-accent" />
          )}
          <div className="min-w-0">
            <p className="font-medium" data-testid="search-index-status">
              {SEARCH_INDEX_LABELS[index.status]}
            </p>
            <p className="mt-0.5 text-sm text-ink-muted">
              {index.status === "READY"
                ? `${index.chunkCount ?? 0} passages indexed${index.completedAt ? ` on ${formatDateTime(index.completedAt)}` : ""}.`
                : index.status === "FAILED"
                  ? "The wording could not be indexed, so this version cannot be searched yet."
                  : index.status === "NOT_PUBLISHED"
                    ? "Only published versions can be searched. Review and publish this version first."
                    : "Published versions are indexed for search. This takes under a minute."}
            </p>
          </div>
        </div>
        {index.status === "READY" ? (
          <Link
            href={`/policies/${policyId}/versions/${versionId}/search`}
            className="inline-flex h-9 items-center gap-1.5 rounded-control bg-accent px-3.5 text-sm font-medium text-white hover:bg-accent-hover"
          >
            <Search aria-hidden className="size-4" />
            Search this version
          </Link>
        ) : null}
        {index.status === "FAILED" && canManage ? (
          <Button variant="secondary" loading={busy} onClick={() => void retry()}>
            {busy ? null : <RotateCw aria-hidden className="size-4" />}
            Retry indexing
          </Button>
        ) : null}
      </div>
      {error ? (
        <div className="mt-3">
          <Notice tone="critical" title={error} />
        </div>
      ) : null}
    </Panel>
  );
}
