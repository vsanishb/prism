"use client";

import { FileText, Layers, Plus, Upload } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, Notice } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { ApiError, apiRequest, apiUpload, errorMessage } from "@/lib/client-api";
import { formatDate, formatMoney } from "@/lib/format";
import type { PolicyDetail } from "@/lib/types";

function AddVersionDialog({ policyId, open, onOpenChange }: { policyId: string; open: boolean; onOpenChange: (open: boolean) => void }) {
  const router = useRouter();
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [effectiveTo, setEffectiveTo] = useState("");
  const [sumInsured, setSumInsured] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      await apiRequest("POST", `/api/v1/policies/${policyId}/versions`, {
        effectiveFrom: effectiveFrom || null,
        effectiveTo: effectiveTo || null,
        sumInsured: sumInsured || null,
      });
      onOpenChange(false);
      router.refresh();
    } catch (failure) {
      if (failure instanceof ApiError && failure.fields.length > 0) {
        setFields(Object.fromEntries(failure.fields.map((item) => [item.field, item.message])));
      } else {
        setError(errorMessage(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Add a version" description="Periods cannot overlap, so that each treatment date maps to exactly one version.">
      <form onSubmit={submit} noValidate className="space-y-4">
        {error ? <Notice tone="critical" title={error} /> : null}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Effective from" htmlFor="effectiveFrom" error={fields.effectiveFrom}>
            <Input id="effectiveFrom" type="date" value={effectiveFrom} onChange={(event) => setEffectiveFrom(event.target.value)} />
          </Field>
          <Field label="Effective to" htmlFor="effectiveTo" error={fields.effectiveTo}>
            <Input id="effectiveTo" type="date" value={effectiveTo} onChange={(event) => setEffectiveTo(event.target.value)} />
          </Field>
        </div>
        <Field label="Sum insured (₹)" htmlFor="sumInsured" error={fields.sumInsured}>
          <Input id="sumInsured" inputMode="decimal" value={sumInsured} onChange={(event) => setSumInsured(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Add version
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

export function PolicyVersions({
  policyId,
  versions,
  canManage,
}: {
  policyId: string;
  versions: PolicyDetail["versions"];
  canManage: boolean;
}) {
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  const [uploading, setUploading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const target = useRef<string | null>(null);

  async function onPicked(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    const versionId = target.current;
    event.target.value = "";
    if (!file || !versionId) {
      return;
    }
    setUploading(versionId);
    setError(null);
    try {
      await apiUpload(`/api/v1/policies/${policyId}/versions/${versionId}/wording`, file);
      router.refresh();
    } catch (failure) {
      setError(`${file.name} was not uploaded. ${errorMessage(failure)}`);
    } finally {
      setUploading(null);
    }
  }

  return (
    <>
      <div className="mb-3 flex items-center justify-between">
        <SectionTitle>Versions</SectionTitle>
        {canManage ? (
          <Button variant="secondary" onClick={() => setAdding(true)}>
            <Plus aria-hidden className="size-4" />
            Add version
          </Button>
        ) : null}
      </div>
      {error ? (
        <div className="mb-4">
          <Notice tone="critical" title={error} />
        </div>
      ) : null}
      <Panel className="divide-y divide-line">
        {versions.length === 0 ? (
          <EmptyState icon={Layers} title="No versions yet">
            A claim can be submitted only when a version covers its admission date.
          </EmptyState>
        ) : (
          versions.map(({ version, documents }) => {
            const wording = documents.find((document) => document.status === "ACTIVE");
            return (
              <div key={version.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                <div>
                  <p className="font-medium">Version {version.versionNumber}</p>
                  <p className="text-[13px] text-ink-muted">
                    {formatDate(version.effectiveFrom)} to {formatDate(version.effectiveTo)} · Sum insured{" "}
                    {formatMoney(version.sumInsured, version.currency)}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {wording ? (
                    <Link
                      href={`/documents/${wording.id}?back=/policies/${policyId}`}
                      className="inline-flex items-center gap-1.5 text-[13px] font-medium text-accent"
                    >
                      <FileText aria-hidden className="size-4" />
                      View wording
                    </Link>
                  ) : (
                    <span className="text-[13px] text-ink-muted">No wording uploaded</span>
                  )}
                  {canManage ? (
                    <Button
                      variant="ghost"
                      loading={uploading === version.id}
                      onClick={() => {
                        target.current = version.id;
                        picker.current?.click();
                      }}
                    >
                      {uploading === version.id ? null : <Upload aria-hidden className="size-4" />}
                      {wording ? "Replace" : "Upload wording"}
                    </Button>
                  ) : null}
                </div>
              </div>
            );
          })
        )}
      </Panel>
      <input ref={picker} type="file" aria-label="Upload policy wording" accept="application/pdf" onChange={onPicked} className="sr-only" />
      <AddVersionDialog policyId={policyId} open={adding} onOpenChange={setAdding} />
    </>
  );
}
