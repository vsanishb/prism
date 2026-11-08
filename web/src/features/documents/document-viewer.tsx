"use client";

import { ChevronLeft, ChevronRight, CircleAlert, Download, ZoomIn, ZoomOut } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { Badge, EmptyState, Skeleton } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { formatBytes, formatDateTime } from "@/lib/format";
import { DOCUMENT_TYPE_LABELS } from "@/lib/labels";
import type { DocumentRecord } from "@/lib/types";

const ZOOM_LEVELS = [60, 80, 100];
const CONTROL = "rounded-control p-2 text-ink-muted transition-colors hover:bg-subtle hover:text-ink disabled:opacity-40 disabled:hover:bg-transparent";

/** Pages arrive as server-rendered images, so that untrusted PDFs never run in the browser. */
export function DocumentViewer({ document, backHref }: { document: DocumentRecord; backHref: string }) {
  const [page, setPage] = useState(1);
  const [zoomIndex, setZoomIndex] = useState(1);
  const [loadedPage, setLoadedPage] = useState<number | null>(null);
  const [failedPage, setFailedPage] = useState<number | null>(null);
  const zoom = ZOOM_LEVELS[zoomIndex] ?? 80;

  return (
    <>
      <Link href={backHref} className="mb-4 inline-flex items-center gap-1 text-sm text-ink-muted hover:text-ink">
        <ChevronLeft aria-hidden className="size-4" />
        Back
      </Link>
      <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="truncate text-lg font-semibold tracking-tight">{document.originalFilename}</h1>
          <p className="mt-0.5 text-sm text-ink-muted">
            {DOCUMENT_TYPE_LABELS[document.documentType]} · {formatBytes(document.sizeBytes)} · Uploaded {formatDateTime(document.uploadedAt)}
          </p>
        </div>
        {document.status !== "ACTIVE" ? <Badge tone="caution">{document.status === "SUPERSEDED" ? "Replaced" : "Removed"}</Badge> : null}
      </header>

      <Panel>
        <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-2">
          <div className="flex items-center gap-1">
            <button type="button" aria-label="Previous page" disabled={page <= 1} onClick={() => setPage(page - 1)} className={CONTROL}>
              <ChevronLeft aria-hidden className="size-4" />
            </button>
            <span className="min-w-20 text-center text-sm tabular-nums">
              Page {page} of {document.pageCount}
            </span>
            <button
              type="button"
              aria-label="Next page"
              disabled={page >= document.pageCount}
              onClick={() => setPage(page + 1)}
              className={CONTROL}
            >
              <ChevronRight aria-hidden className="size-4" />
            </button>
          </div>
          <div className="flex items-center gap-1">
            <button type="button" aria-label="Zoom out" disabled={zoomIndex === 0} onClick={() => setZoomIndex(zoomIndex - 1)} className={CONTROL}>
              <ZoomOut aria-hidden className="size-4" />
            </button>
            <button
              type="button"
              aria-label="Zoom in"
              disabled={zoomIndex === ZOOM_LEVELS.length - 1}
              onClick={() => setZoomIndex(zoomIndex + 1)}
              className={CONTROL}
            >
              <ZoomIn aria-hidden className="size-4" />
            </button>
            <a href={`/api/v1/documents/${document.id}/content`} aria-label="Download original" title="Download original" className={CONTROL}>
              <Download aria-hidden className="size-4" />
            </a>
          </div>
        </div>
        <div className="flex justify-center overflow-auto rounded-b-panel bg-subtle p-6">
          {failedPage === page ? (
            <EmptyState icon={CircleAlert} title="This page could not be shown">
              Try again, or download the original.
            </EmptyState>
          ) : (
            <div className="relative" style={{ width: `${zoom}%` }}>
              {loadedPage !== page ? <Skeleton className="absolute inset-0 min-h-96" /> : null}
              {/* Authenticated, per-request content: next/image optimization does not apply. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                key={page}
                src={`/api/v1/documents/${document.id}/pages/${page}`}
                alt={`Page ${page} of ${document.originalFilename}`}
                onLoad={() => setLoadedPage(page)}
                onError={() => setFailedPage(page)}
                className="block min-h-96 w-full bg-white shadow-sm"
              />
            </div>
          )}
        </div>
      </Panel>
    </>
  );
}
