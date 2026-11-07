"use client";

import { CircleAlert } from "lucide-react";
import { useState } from "react";
import { EmptyState, Skeleton } from "@/components/ui/feedback";

type Region = readonly (string | number)[] | null | undefined;

/**
 * A server-rendered document page, with the cited text outlined when the page has a text layer.
 * Pages arrive as images, so untrusted PDFs never run in the browser (D-051).
 */
export function SourcePage({ documentId, page, region, label }: { documentId: string; page: number; region: Region; label: string }) {
  const url = `/api/v1/documents/${documentId}/pages/${page}`;
  const [loaded, setLoaded] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const box = region?.map(Number);
  const [left, top, right, bottom] = box?.length === 4 ? box : [];

  if (failed === url) {
    return (
      <EmptyState icon={CircleAlert} title="This page could not be shown">
        Open the document to try again.
      </EmptyState>
    );
  }
  return (
    <div className="relative">
      {loaded !== url ? <Skeleton className="absolute inset-0 min-h-96" /> : null}
      {/* Authenticated, per-request content: next/image optimization does not apply. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        key={url}
        src={url}
        alt={label}
        onLoad={() => setLoaded(url)}
        onError={() => setFailed(url)}
        className="block min-h-96 w-full bg-white shadow-sm"
      />
      {left !== undefined && top !== undefined && right !== undefined && bottom !== undefined ? (
        <span
          data-testid="source-highlight"
          aria-hidden
          className="pointer-events-none absolute rounded-[2px] bg-accent/20 outline-2 outline-accent"
          style={{ left: `${left * 100}%`, top: `${top * 100}%`, width: `${(right - left) * 100}%`, height: `${(bottom - top) * 100}%` }}
        />
      ) : null}
    </div>
  );
}
