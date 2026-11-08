"use client";

import { ChevronLeft, ChevronRight, FileSearch } from "lucide-react";
import { SourcePage } from "@/components/source-page";
import { EmptyState } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { DOCUMENT_TYPE_LABELS } from "@/lib/labels";
import type { DocumentReference } from "@/lib/types";

export interface SourceTarget {
  key: string;
  documentId: string;
  page: number;
  region: readonly (string | number)[] | null;
  label: string;
}

/** The claim's documents, a page at a time, with the cited text outlined. Statements anywhere on the workspace open here. */
export function SourcePane({
  documents,
  source,
  onOpen,
}: {
  documents: DocumentReference[];
  source: SourceTarget | null;
  onOpen: (source: SourceTarget) => void;
}) {
  const current = documents.find((document) => document.id === source?.documentId);

  function browse(documentId: string, page: number) {
    const document = documents.find((candidate) => candidate.id === documentId);
    if (!document) {
      return;
    }
    const label = `${DOCUMENT_TYPE_LABELS[document.documentType as keyof typeof DOCUMENT_TYPE_LABELS] ?? "Document"}, page ${page}`;
    onOpen({ key: `browse-${documentId}-${page}`, documentId, page, region: null, label });
  }

  return (
    <div>
      <SectionTitle>Source documents</SectionTitle>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {documents.map((document) => (
          <button
            key={document.id}
            type="button"
            onClick={() => browse(document.id, 1)}
            aria-pressed={current?.id === document.id}
            className={`rounded-control border px-2.5 py-1 text-xs ${
              current?.id === document.id ? "border-accent bg-accent-soft text-accent" : "border-line hover:bg-subtle"
            }`}
          >
            {DOCUMENT_TYPE_LABELS[(document.classifiedType ?? document.documentType) as keyof typeof DOCUMENT_TYPE_LABELS] ??
              document.originalFilename}
          </button>
        ))}
      </div>
      {source ? (
        <>
          <div className="mb-2 flex items-center justify-between gap-2">
            <p className="text-sm font-medium" data-testid="source-label">
              {source.label}
            </p>
            {current && current.pageCount > 1 ? (
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  aria-label="Previous page"
                  disabled={source.page <= 1}
                  onClick={() => browse(current.id, source.page - 1)}
                  className="rounded-control p-1 hover:bg-subtle disabled:opacity-40"
                >
                  <ChevronLeft aria-hidden className="size-4" />
                </button>
                <span className="text-xs text-ink-muted tabular-nums">
                  {source.page} / {current.pageCount}
                </span>
                <button
                  type="button"
                  aria-label="Next page"
                  disabled={source.page >= current.pageCount}
                  onClick={() => browse(current.id, source.page + 1)}
                  className="rounded-control p-1 hover:bg-subtle disabled:opacity-40"
                >
                  <ChevronRight aria-hidden className="size-4" />
                </button>
              </div>
            ) : null}
          </div>
          <SourcePage documentId={source.documentId} page={source.page} region={source.region} label={source.label} />
        </>
      ) : (
        <Panel>
          <EmptyState icon={FileSearch} title="Choose a document or a statement">
            Any fact, citation, or risk indicator opens its page here, with the text outlined.
          </EmptyState>
        </Panel>
      )}
    </div>
  );
}
