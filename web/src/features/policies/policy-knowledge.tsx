"use client";

import { CircleAlert, CircleCheck, FileSearch, LoaderCircle, Pencil, RotateCw, TriangleAlert } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { SourcePage } from "@/components/source-page";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Badge, EmptyState, Notice } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { ApiError, apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime } from "@/lib/format";
import {
  POLICY_VALUE_PROBLEMS,
  PROVISION_TYPE_LABELS,
  RELATION_LABELS,
  policyFindingMessage,
  policyValueText,
} from "@/lib/labels";
import type { PolicyKnowledge, PolicyProvision, PolicyValue, ProvisionType } from "@/lib/types";

const POLL_MILLIS = 3000;
const TYPE_ORDER: ProvisionType[] = ["SCHEDULE", "DEFINITION", "BENEFIT", "WAITING_PERIOD", "EXCLUSION", "PROCEDURE", "CLAUSE"];
type Tab = "values" | "structure" | "findings";

interface Source {
  title: string;
  page: number;
  region: PolicyValue["region"];
  quote: string | null;
  note: string | null;
}

function provisionName(provision: PolicyProvision): string {
  return provision.key ? `${provision.key} ${provision.title}` : provision.title;
}

function valueLabel(value: PolicyValue, currency: string): string {
  const figure = value.review?.correctedValue ?? value.value;
  return figure ? policyValueText(value.kind, figure, value.unit, currency) : value.rawValue;
}

function ValueRow({
  reviewPath,
  value,
  provision,
  knowledge,
  canReview,
  selected,
  onSelect,
}: {
  reviewPath: string;
  value: PolicyValue;
  provision: PolicyProvision;
  knowledge: PolicyKnowledge;
  canReview: boolean;
  selected: boolean;
  onSelect: () => void;
}) {
  const router = useRouter();
  const [correcting, setCorrecting] = useState(false);
  const [correction, setCorrection] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send(body: Record<string, string>) {
    setBusy(true);
    setError(null);
    try {
      await apiRequest("POST", reviewPath, body);
      setCorrecting(false);
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  function correct(event: FormEvent) {
    event.preventDefault();
    void send({ decision: "CORRECTED", correctedValue: correction, note });
  }

  const reviewed = value.review;
  return (
    <li className={selected ? "bg-accent-soft" : ""}>
      <button type="button" aria-pressed={selected} onClick={onSelect} className="flex w-full items-start gap-3 px-4 py-3 text-left">
        <span className="min-w-0 flex-1">
          <span className="block text-xs text-ink-muted">
            {provisionName(provision)} · {value.name}
          </span>
          <span className="mt-0.5 block text-[13px] font-medium break-words">
            {valueLabel(value, knowledge.currency)}
            {value.qualifier ? <span className="font-normal text-ink-muted"> {value.qualifier}</span> : null}
          </span>
          {reviewed ? (
            <span className="mt-0.5 block text-xs text-ink-muted">
              {reviewed.decision === "CORRECTED" ? `Corrected from ${value.value ?? value.rawValue}: ${reviewed.note}` : "Confirmed"} by{" "}
              {reviewed.reviewerName}
            </span>
          ) : value.problem ? (
            <span className="mt-0.5 block text-xs text-caution">{POLICY_VALUE_PROBLEMS[value.problem] ?? "Could not be confirmed."}</span>
          ) : null}
        </span>
        {reviewed ? (
          <Badge tone="positive">{reviewed.decision === "CORRECTED" ? "Corrected" : "Confirmed"}</Badge>
        ) : (
          <Badge tone={value.state === "PRESENT" ? "accent" : "caution"}>{value.state === "PRESENT" ? "To confirm" : "Uncertain"}</Badge>
        )}
      </button>
      {canReview ? (
        <div className="px-4 pb-3">
          {error ? (
            <div className="mb-2">
              <Notice tone="critical" title={error} />
            </div>
          ) : null}
          {correcting ? (
            <form onSubmit={correct} className="space-y-2" noValidate>
              <div className="grid gap-2 sm:grid-cols-[10rem_minmax(0,1fr)]">
                <Field label="Correct value" htmlFor={`value-${value.id}`}>
                  <Input id={`value-${value.id}`} value={correction} onChange={(event) => setCorrection(event.target.value)} />
                </Field>
                <Field label="Reason" htmlFor={`note-${value.id}`}>
                  <Input id={`note-${value.id}`} value={note} onChange={(event) => setNote(event.target.value)} />
                </Field>
              </div>
              <div className="flex gap-2">
                <Button type="submit" loading={busy}>
                  Save correction
                </Button>
                <Button variant="ghost" onClick={() => setCorrecting(false)}>
                  Cancel
                </Button>
              </div>
            </form>
          ) : (
            <div className="flex flex-wrap gap-2">
              {value.value !== null ? (
                <Button variant="secondary" loading={busy} onClick={() => void send({ decision: "CONFIRMED" })}>
                  {busy ? null : <CircleCheck aria-hidden className="size-4" />}
                  {reviewed ? "Confirm again" : "Confirm"}
                </Button>
              ) : null}
              <Button
                variant="ghost"
                onClick={() => {
                  setCorrection(value.review?.correctedValue ?? value.value ?? "");
                  setCorrecting(true);
                }}
              >
                <Pencil aria-hidden className="size-4" />
                Correct
              </Button>
            </div>
          )}
        </div>
      ) : null}
    </li>
  );
}

function SourcePanel({ source, knowledge }: { source: Source | null; knowledge: PolicyKnowledge }) {
  if (!source || !knowledge.documentId) {
    return (
      <Panel>
        <EmptyState icon={FileSearch} title="Choose an item">
          Its page in the wording appears here, with the text outlined.
        </EmptyState>
      </Panel>
    );
  }
  return (
    <Panel>
      <div className="border-b border-line px-4 py-3">
        <p className="text-[13px] font-medium">{source.title}</p>
        <p className="mt-0.5 text-xs text-ink-muted">
          {knowledge.documentName} · Page {source.page}
        </p>
      </div>
      {source.quote ? <blockquote className="border-b border-line px-4 py-2.5 text-[13px] text-ink-muted">“{source.quote}”</blockquote> : null}
      {source.note ? (
        <p className="flex gap-2 border-b border-line bg-caution-soft px-4 py-2.5 text-xs text-caution">
          <TriangleAlert aria-hidden className="mt-px size-3.5 shrink-0" />
          {source.note}
        </p>
      ) : null}
      <div className="max-h-[70vh] overflow-auto rounded-b-panel bg-subtle p-3">
        <SourcePage
          key={`${source.page}-${source.title}`}
          documentId={knowledge.documentId}
          page={source.page}
          region={source.region}
          label={`Page ${source.page} of ${knowledge.documentName ?? "the wording"}`}
        />
      </div>
    </Panel>
  );
}

function Structure({ knowledge, onOpen }: { knowledge: PolicyKnowledge; onOpen: (provision: PolicyProvision) => void }) {
  const byId = new Map(knowledge.provisions.map((provision) => [provision.id, provision]));
  return (
    <div className="space-y-6">
      {TYPE_ORDER.map((type) => {
        const items = knowledge.provisions.filter((provision) => provision.type === type);
        if (items.length === 0) {
          return null;
        }
        return (
          <div key={type}>
            <SectionTitle>{PROVISION_TYPE_LABELS[type]}</SectionTitle>
            <Panel className="divide-y divide-line">
              {items.map((provision) => {
                const relations = knowledge.relations.filter((relation) => relation.fromProvisionId === provision.id);
                return (
                  <button
                    key={provision.id}
                    type="button"
                    onClick={() => onOpen(provision)}
                    className="block w-full px-4 py-3 text-left transition-colors hover:bg-subtle"
                  >
                    <span className="flex flex-wrap items-baseline justify-between gap-2">
                      <span className="text-[13px] font-medium">{provisionName(provision)}</span>
                      <span className="text-xs text-ink-muted">
                        {provision.pageStart === provision.pageEnd ? `Page ${provision.pageStart}` : `Pages ${provision.pageStart} to ${provision.pageEnd}`}
                      </span>
                    </span>
                    <span className="mt-1 block text-[13px] text-ink-muted break-words">{provision.text}</span>
                    {provision.exceptions.length > 0 ? (
                      <span className="mt-2 block space-y-1">
                        {provision.exceptions.map((exception) => (
                          <span key={exception} className="block rounded-control bg-caution-soft px-2 py-1 text-xs text-caution">
                            Exception: {exception}
                          </span>
                        ))}
                      </span>
                    ) : null}
                    {provision.values.length > 0 || relations.length > 0 ? (
                      <span className="mt-2 flex flex-wrap gap-1.5">
                        {provision.values.map((value) => (
                          <Badge key={value.id} tone={value.state === "PRESENT" ? "neutral" : "caution"}>
                            {value.name}: {valueLabel(value, knowledge.currency)}
                          </Badge>
                        ))}
                        {relations.map((relation, index) => (
                          <Badge key={`${relation.targetKey}-${index}`} tone={relation.toProvisionId ? "accent" : "caution"}>
                            {RELATION_LABELS[relation.relation]} {relation.toProvisionId ? provisionName(byId.get(relation.toProvisionId) as PolicyProvision) : `${relation.targetKey} (not found)`}
                          </Badge>
                        ))}
                      </span>
                    ) : null}
                    {!provision.textVerified ? (
                      <span className="mt-2 block text-xs text-caution">This text does not fully match the page. Compare it with the source.</span>
                    ) : null}
                  </button>
                );
              })}
            </Panel>
          </div>
        );
      })}
    </div>
  );
}

function RejectDialog({ path, open, onOpenChange }: { path: string; open: boolean; onOpenChange: (open: boolean) => void }) {
  const router = useRouter();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reasonError, setReasonError] = useState<string | undefined>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setReasonError(undefined);
    try {
      await apiRequest("POST", `${path}/rejection`, { reason });
      onOpenChange(false);
      router.refresh();
    } catch (failure) {
      const field = failure instanceof ApiError ? failure.fields.find((item) => item.field === "reason") : undefined;
      if (field) {
        setReasonError(field.message);
      } else {
        setError(errorMessage(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Reject this version" description="The wording stays on record. Upload a corrected wording to read it again.">
      <form onSubmit={submit} noValidate className="space-y-4">
        {error ? <Notice tone="critical" title={error} /> : null}
        <Field label="Reason" htmlFor="rejection-reason" error={reasonError}>
          <Input id="rejection-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" loading={busy}>
            Reject version
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** Review of one version's knowledge: every critical value is confirmed by a person (D-030). */
export function PolicyKnowledgeReview({ policyId, knowledge, canManage }: { policyId: string; knowledge: PolicyKnowledge; canManage: boolean }) {
  const router = useRouter();
  const path = `/api/v1/policies/${policyId}/versions/${knowledge.versionId}/knowledge`;
  const [tab, setTab] = useState<Tab>("values");
  const [source, setSource] = useState<Source | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sourcePane = useRef<HTMLDivElement>(null);
  const reading = knowledge.knowledgeStatus === "PROCESSING";
  const canReview = canManage && knowledge.knowledgeStatus === "IN_REVIEW";

  useEffect(() => {
    if (!reading) {
      return;
    }
    const timer = setInterval(() => {
      if (!document.hidden) {
        router.refresh();
      }
    }, POLL_MILLIS);
    return () => clearInterval(timer);
  }, [reading, router]);

  function open(next: Source, key: string) {
    setSource(next);
    setSelected(key);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      // After the new source renders, and without animation, which a later layout change can cut short.
      requestAnimationFrame(() => sourcePane.current?.scrollIntoView({ block: "start" }));
    }
  }

  async function act(action: "publication" | "retry") {
    setBusy(true);
    setError(null);
    try {
      await apiRequest("POST", `${path}/${action}`);
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  if (reading || knowledge.provisions.length === 0) {
    const failed = knowledge.batches.some((batch) => batch.status === "FAILED");
    return (
      <Panel>
        <ol className="divide-y divide-line">
          {knowledge.batches.length === 0 ? (
            <li className="flex items-center gap-3 px-5 py-3 text-[13px]">
              <LoaderCircle aria-hidden className="size-4 animate-spin text-accent" />
              Waiting to start reading
            </li>
          ) : (
            knowledge.batches.map((batch) => (
              <li key={batch.firstPage} className="flex items-center gap-3 px-5 py-3 text-[13px]">
                {batch.status === "COMPLETED" ? (
                  <CircleCheck aria-hidden className="size-4 text-positive" />
                ) : batch.status === "FAILED" ? (
                  <CircleAlert aria-hidden className="size-4 text-critical" />
                ) : (
                  <LoaderCircle aria-hidden className="size-4 animate-spin text-accent" />
                )}
                <span className="flex-1">
                  {batch.firstPage === 0 ? "Assembling provisions" : `Pages ${batch.firstPage} to ${batch.lastPage}`}
                </span>
                <span className="text-xs text-ink-muted">
                  {batch.status === "COMPLETED" ? "Done" : batch.status === "FAILED" ? "Failed" : "In progress"}
                </span>
              </li>
            ))
          )}
        </ol>
        {failed && canManage ? (
          <div className="flex items-center justify-between gap-3 border-t border-line px-5 py-3.5">
            <p className="text-[13px] text-critical">Part of the wording could not be read. Retry, or upload a clearer copy.</p>
            <Button variant="secondary" loading={busy} onClick={() => void act("retry")}>
              {busy ? null : <RotateCw aria-hidden className="size-4" />}
              Retry
            </Button>
          </div>
        ) : null}
        {error ? (
          <div className="border-t border-line p-5">
            <Notice tone="critical" title={error} />
          </div>
        ) : null}
      </Panel>
    );
  }

  const critical = knowledge.provisions.flatMap((provision) =>
    provision.values.filter((value) => value.critical).map((value) => ({ provision, value })),
  );
  const reviewed = critical.filter((item) => item.value.review).length;

  return (
    <>
      {knowledge.knowledgeStatus === "PUBLISHED" ? (
        <div className="mb-6">
          <Notice tone="positive" title={`Published by ${knowledge.publishedByName ?? "a policy manager"}`}>
            {knowledge.publishedAt ? formatDateTime(knowledge.publishedAt) : null}. This version is final. Changes need a new version.
          </Notice>
        </div>
      ) : null}
      {knowledge.knowledgeStatus === "REJECTED" ? (
        <div className="mb-6">
          <Notice tone="critical" title="Rejected">
            {knowledge.rejectionReason}
          </Notice>
        </div>
      ) : null}
      {knowledge.knowledgeStatus === "IN_REVIEW" ? (
        <Panel className="mb-6 p-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <p className="font-medium">
                {reviewed} of {critical.length} critical values reviewed
              </p>
              {knowledge.blockers.length > 0 ? (
                <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[13px] text-ink-muted">
                  {knowledge.blockers.map((blocker) => (
                    <li key={blocker.code}>{blocker.message}</li>
                  ))}
                </ul>
              ) : (
                <p className="mt-1 text-[13px] text-ink-muted">Ready to publish. A policy manager other than the uploader must publish it.</p>
              )}
            </div>
            {canManage ? (
              <div className="flex gap-2">
                <Button variant="ghost" onClick={() => setRejecting(true)}>
                  Reject
                </Button>
                <Button loading={busy} disabled={knowledge.blockers.length > 0} onClick={() => void act("publication")}>
                  Publish
                </Button>
              </div>
            ) : null}
          </div>
          {error ? (
            <div className="mt-3">
              <Notice tone="critical" title={error} />
            </div>
          ) : null}
        </Panel>
      ) : null}

      <div role="tablist" aria-label="Knowledge views" className="mb-4 flex gap-1 border-b border-line">
        {(
          [
            ["values", `Values to confirm (${critical.length})`],
            ["structure", `Structure (${knowledge.provisions.length})`],
            ["findings", `Findings (${knowledge.findings.length})`],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`-mb-px border-b-2 px-3 py-2 text-[13px] font-medium ${tab === key ? "border-accent text-ink" : "border-transparent text-ink-muted hover:text-ink"}`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <div className="min-w-0">
          {tab === "values" ? (
            <Panel>
              <ul className="divide-y divide-line">
                {critical.map(({ provision, value }) => (
                  <ValueRow
                    key={value.id}
                    reviewPath={`${path}/values/${value.id}/review`}
                    value={value}
                    provision={provision}
                    knowledge={knowledge}
                    canReview={canReview}
                    selected={selected === value.id}
                    onSelect={() =>
                      open(
                        {
                          title: `${provisionName(provision)} · ${value.name}`,
                          page: value.page ?? provision.pageStart,
                          region: value.region,
                          quote: value.quote,
                          note: value.problem ? (POLICY_VALUE_PROBLEMS[value.problem] ?? null) : null,
                        },
                        value.id,
                      )
                    }
                  />
                ))}
              </ul>
            </Panel>
          ) : null}
          {tab === "structure" ? (
            <Structure
              knowledge={knowledge}
              onOpen={(provision) =>
                open(
                  { title: provisionName(provision), page: provision.pageStart, region: provision.region, quote: provision.quote, note: null },
                  provision.id,
                )
              }
            />
          ) : null}
          {tab === "findings" ? (
            <Panel>
              {knowledge.findings.length === 0 ? (
                <EmptyState icon={CircleCheck} title="No findings">
                  Every check passed.
                </EmptyState>
              ) : (
                <ul className="divide-y divide-line">
                  {knowledge.findings.map((finding, index) => (
                    <li key={`${finding.code}-${index}`} className="flex gap-2.5 px-4 py-3 text-[13px]">
                      <TriangleAlert
                        aria-hidden
                        className={`mt-0.5 size-4 shrink-0 ${finding.severity === "ERROR" ? "text-critical" : finding.severity === "WARNING" ? "text-caution" : "text-ink-faint"}`}
                      />
                      <span>{policyFindingMessage(finding, knowledge.provisions, knowledge.currency)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          ) : null}
        </div>
        <div ref={sourcePane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
          <SectionTitle>Source</SectionTitle>
          <SourcePanel source={source} knowledge={knowledge} />
        </div>
      </div>
      <RejectDialog path={path} open={rejecting} onOpenChange={setRejecting} />
    </>
  );
}
