"use client";

import { FileText, RefreshCw, Trash2, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, type ChangeEvent, type DragEvent } from "react";
import { Badge, EmptyState, Notice } from "@/components/ui/feedback";
import { Select } from "@/components/ui/field";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { apiRequest, apiUpload, errorMessage } from "@/lib/client-api";
import { formatBytes, formatDateTime } from "@/lib/format";
import { DOCUMENT_TYPE_LABELS } from "@/lib/labels";
import { CLAIM_DOCUMENT_TYPES, type ClaimDetail, type DocumentRecord, type DocumentType } from "@/lib/types";

const ACCEPTED = "application/pdf,image/png,image/jpeg,image/tiff";

type UploadState = { name: string; status: "uploading" } | { name: string; status: "failed"; message: string };

function DocumentRow({
  claimId,
  document,
  canRemove,
  canReplace,
  onReplace,
  onRemove,
}: {
  claimId: string;
  document: DocumentRecord;
  canRemove: boolean;
  canReplace: boolean;
  onReplace: (document: DocumentRecord) => void;
  onRemove: (document: DocumentRecord) => void;
}) {
  const active = document.status === "ACTIVE";
  return (
    <div className={`flex items-center gap-3 px-5 py-3.5 ${active ? "" : "opacity-60"}`}>
      <FileText aria-hidden className="size-5 shrink-0 text-ink-faint" />
      <div className="min-w-0 flex-1">
        <Link href={`/documents/${document.id}?back=/claims/${claimId}`} className="block truncate text-sm font-medium hover:text-accent">
          {document.originalFilename}
        </Link>
        <p className="text-xs text-ink-muted">
          {DOCUMENT_TYPE_LABELS[document.documentType]} · {document.pageCount} {document.pageCount === 1 ? "page" : "pages"} ·{" "}
          {formatBytes(document.sizeBytes)} · {formatDateTime(document.uploadedAt)}
        </p>
      </div>
      {document.status === "SUPERSEDED" ? <Badge>Replaced</Badge> : null}
      {document.status === "REMOVED" ? <Badge>Removed</Badge> : null}
      {active && canReplace ? (
        <button
          type="button"
          aria-label={`Replace ${document.originalFilename}`}
          title="Replace"
          onClick={() => onReplace(document)}
          className="rounded-control p-1.5 text-ink-muted transition-colors hover:bg-subtle hover:text-ink"
        >
          <RefreshCw aria-hidden className="size-4" />
        </button>
      ) : null}
      {active && canRemove ? (
        <button
          type="button"
          aria-label={`Remove ${document.originalFilename}`}
          title="Remove"
          onClick={() => onRemove(document)}
          className="rounded-control p-1.5 text-ink-muted transition-colors hover:bg-critical-soft hover:text-critical"
        >
          <Trash2 aria-hidden className="size-4" />
        </button>
      ) : null}
    </div>
  );
}

export function DocumentPanel({ claim }: { claim: ClaimDetail }) {
  const router = useRouter();
  const claimId = claim.summary.id;
  const canAdd = claim.availableActions.includes("ADD_DOCUMENT");
  const canRemove = claim.availableActions.includes("REMOVE_DOCUMENT");
  const [documentType, setDocumentType] = useState<DocumentType>("HOSPITAL_BILL");
  const [uploads, setUploads] = useState<UploadState[]>([]);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const replacer = useRef<HTMLInputElement>(null);
  const replacing = useRef<DocumentRecord | null>(null);

  async function uploadAll(files: File[], path: (file: File) => string) {
    setError(null);
    setUploads(files.map((file) => ({ name: file.name, status: "uploading" })));
    const failures: UploadState[] = [];
    for (const file of files) {
      try {
        await apiUpload(path(file), file);
      } catch (failure) {
        failures.push({ name: file.name, status: "failed", message: errorMessage(failure) });
      }
    }
    setUploads(failures);
    router.refresh();
  }

  function addFiles(files: File[]) {
    if (files.length > 0) {
      void uploadAll(files, () => `/api/v1/claims/${claimId}/documents?documentType=${documentType}`);
    }
  }

  function onPicked(event: ChangeEvent<HTMLInputElement>) {
    addFiles(Array.from(event.target.files ?? []));
    event.target.value = "";
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    addFiles(Array.from(event.dataTransfer.files));
  }

  function onReplacementPicked(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    const target = replacing.current;
    event.target.value = "";
    if (file && target) {
      void uploadAll([file], () => `/api/v1/claims/${claimId}/documents/${target.id}/replacement`);
    }
  }

  async function remove(document: DocumentRecord) {
    setError(null);
    try {
      await apiRequest("DELETE", `/api/v1/claims/${claimId}/documents/${document.id}`);
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    }
  }

  return (
    <div>
      <SectionTitle>Documents</SectionTitle>
      <Panel>
        {claim.documents.length === 0 ? (
          <EmptyState icon={FileText} title="No documents yet">
            {canAdd ? "Add the claim form, bills, and medical records." : null}
          </EmptyState>
        ) : (
          <div className="divide-y divide-line">
            {claim.documents.map((document) => (
              <DocumentRow
                key={document.id}
                claimId={claimId}
                document={document}
                canRemove={canRemove}
                canReplace={canAdd}
                onReplace={(target) => {
                  replacing.current = target;
                  replacer.current?.click();
                }}
                onRemove={remove}
              />
            ))}
          </div>
        )}

        {canAdd ? (
          <div className="space-y-3 border-t border-line p-5">
            {error ? <Notice tone="critical" title={error} /> : null}
            {uploads.map((upload) =>
              upload.status === "failed" ? (
                <Notice key={upload.name} tone="critical" title={`${upload.name} was not uploaded`}>
                  {upload.message}
                </Notice>
              ) : (
                <p key={upload.name} role="status" className="flex items-center gap-2 text-sm text-ink-muted">
                  <span aria-hidden className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
                  Uploading {upload.name}
                </p>
              ),
            )}
            <div className="flex flex-wrap items-center gap-3">
              <div className="w-48">
                <Select aria-label="Document type" value={documentType} onChange={(event) => setDocumentType(event.target.value as DocumentType)}>
                  {CLAIM_DOCUMENT_TYPES.map((value) => (
                    <option key={value} value={value}>
                      {DOCUMENT_TYPE_LABELS[value]}
                    </option>
                  ))}
                </Select>
              </div>
              <button
                type="button"
                onClick={() => picker.current?.click()}
                onDragOver={(event) => {
                  event.preventDefault();
                  setDragging(true);
                }}
                onDragLeave={() => setDragging(false)}
                onDrop={onDrop}
                className={`flex h-9 min-w-48 flex-1 items-center justify-center gap-2 rounded-control border border-dashed text-sm transition-colors ${
                  dragging ? "border-accent bg-accent-soft text-accent" : "border-line-strong text-ink-muted hover:border-accent hover:text-accent"
                }`}
              >
                <Upload aria-hidden className="size-4" />
                Choose or drop files
              </button>
            </div>
            <p className="text-xs text-ink-muted">PDF, PNG, JPEG, or TIFF, up to 25 MB each.</p>
            <input ref={picker} type="file" aria-label="Upload documents" multiple accept={ACCEPTED} onChange={onPicked} className="sr-only" />
            <input ref={replacer} type="file" aria-label="Replacement document" accept={ACCEPTED} onChange={onReplacementPicked} className="sr-only" />
          </div>
        ) : null}
      </Panel>
    </div>
  );
}
