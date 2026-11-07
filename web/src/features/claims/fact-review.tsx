"use client";

import { CircleAlert, FileSearch, TriangleAlert } from "lucide-react";
import { useRef, useState } from "react";
import { Badge, EmptyState, Skeleton } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { formatDate, formatMoney } from "@/lib/format";
import {
  DOCUMENT_TYPE_LABELS,
  FACT_STATE_LABELS,
  fieldLabel,
  findingMessage,
  UNCERTAINTY_MESSAGES,
} from "@/lib/labels";
import type { ClaimFact, ClaimFacts, DocumentReference, FactSource, FactState, Finding, LineItem } from "@/lib/types";

const STATE_TONES = {
  PRESENT: "positive",
  MISSING: "neutral",
  UNCERTAIN: "caution",
  CONFLICTING: "caution",
} as const;

interface Selection {
  key: string;
  title: string;
  sources: FactSource[];
}

function readable(kind: ClaimFact["kind"], value: string, currency: string): string {
  if (kind === "DATE") {
    return formatDate(value);
  }
  return kind === "AMOUNT" ? formatMoney(value, currency) : value;
}

/** Several documents can agree with each other, so sources are not the same count as values. */
export function distinctValues(sources: FactSource[]): number {
  return new Set(sources.filter((source) => source.state === "PRESENT").map((source) => source.value)).size;
}

function lineItemSource(item: LineItem): FactSource {
  return {
    documentId: item.documentId,
    state: item.state,
    confidence: "HIGH",
    rawValue: item.rawAmount,
    value: item.amount,
    page: item.page,
    quote: item.quote,
    region: item.region,
    grounding: item.grounding,
    reason: null,
  };
}

function FactRow({
  fact,
  currency,
  selected,
  onSelect,
}: {
  fact: ClaimFact;
  currency: string;
  selected: boolean;
  onSelect: () => void;
}) {
  const value = fact.value
    ? readable(fact.kind, fact.value, currency)
    : fact.state === "MISSING"
      ? "—"
      : fact.state === "CONFLICTING"
        ? `${distinctValues(fact.sources)} different values`
        : "Could not be read reliably";
  const content = (
    <>
      <span className="min-w-0 flex-1">
        <span className="block text-xs text-ink-muted">{fieldLabel(fact.name)}</span>
        <span className={`mt-0.5 block text-[13px] break-words ${fact.value ? "font-medium" : "text-ink-muted"}`}>{value}</span>
      </span>
      <Badge tone={STATE_TONES[fact.state]}>{FACT_STATE_LABELS[fact.state]}</Badge>
    </>
  );
  if (fact.sources.length === 0) {
    return <div className="flex items-center gap-3 px-4 py-3">{content}</div>;
  }
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={`flex w-full items-center gap-3 px-4 py-3 text-left transition-colors ${selected ? "bg-accent-soft" : "hover:bg-subtle"}`}
    >
      {content}
    </button>
  );
}

function SourceView({
  selection,
  documents,
  currency,
  kind,
}: {
  selection: Selection | null;
  documents: DocumentReference[];
  currency: string;
  kind: ClaimFact["kind"] | undefined;
}) {
  const [sourceIndex, setSourceIndex] = useState(0);
  const [loaded, setLoaded] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  if (!selection) {
    return (
      <Panel>
        <EmptyState icon={FileSearch} title="Choose a fact">
          Its source page appears here, with the value highlighted where the document allows.
        </EmptyState>
      </Panel>
    );
  }
  const source = selection.sources[Math.min(sourceIndex, selection.sources.length - 1)];
  if (!source) {
    return null;
  }
  const document = documents.find((candidate) => candidate.id === source.documentId);
  const imageUrl = `/api/v1/documents/${source.documentId}/pages/${source.page ?? 1}`;
  const region = source.region?.map(Number);

  return (
    <Panel>
      <div className="border-b border-line px-4 py-3">
        <p className="text-[13px] font-medium">{selection.title}</p>
        {selection.sources.length > 1 ? (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {selection.sources.map((candidate, index) => {
              const name = documents.find((item) => item.id === candidate.documentId)?.originalFilename ?? "Document";
              const value = candidate.value && kind ? readable(kind, candidate.value, currency) : (candidate.rawValue ?? "Unclear");
              return (
                <button
                  key={`${candidate.documentId}-${index}`}
                  type="button"
                  aria-pressed={index === sourceIndex}
                  onClick={() => setSourceIndex(index)}
                  className={`rounded-control border px-2 py-1 text-left text-xs transition-colors ${
                    index === sourceIndex ? "border-accent bg-accent-soft" : "border-line hover:bg-subtle"
                  }`}
                >
                  <span className="block font-medium">{value}</span>
                  <span className="block text-ink-muted">{name}</span>
                </button>
              );
            })}
          </div>
        ) : null}
        <p className="mt-1.5 text-xs text-ink-muted">
          {document?.originalFilename ?? "Document"}
          {source.page ? ` · Page ${source.page}` : ""}
        </p>
      </div>
      {source.quote ? (
        <blockquote className="border-b border-line px-4 py-2.5 text-[13px] text-ink-muted">“{source.quote}”</blockquote>
      ) : null}
      {source.reason ? (
        <p className="flex gap-2 border-b border-line bg-caution-soft px-4 py-2.5 text-xs text-caution">
          <TriangleAlert aria-hidden className="mt-px size-3.5 shrink-0" />
          {UNCERTAINTY_MESSAGES[source.reason] ?? "This value could not be confirmed."}
        </p>
      ) : null}
      {source.grounding === "PAGE_ONLY" && !source.reason ? (
        <p className="border-b border-line px-4 py-2.5 text-xs text-ink-muted">
          Scanned page: there is no text to highlight, so check the value on the page.
        </p>
      ) : null}
      <div className="max-h-[70vh] overflow-auto rounded-b-panel bg-subtle p-3">
        {failed === imageUrl ? (
          <EmptyState icon={CircleAlert} title="This page could not be shown">
            Open the document from the claim to try again.
          </EmptyState>
        ) : (
          <div className="relative">
            {loaded !== imageUrl ? <Skeleton className="absolute inset-0 min-h-96" /> : null}
            {/* Authenticated, per-request content: next/image optimization does not apply. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              key={imageUrl}
              src={imageUrl}
              alt={`Page ${source.page ?? 1} of ${document?.originalFilename ?? "the document"}`}
              onLoad={() => setLoaded(imageUrl)}
              onError={() => setFailed(imageUrl)}
              className="block min-h-96 w-full bg-white shadow-sm"
            />
            {region?.length === 4 && region[0] !== undefined && region[1] !== undefined && region[2] !== undefined && region[3] !== undefined ? (
              <span
                data-testid="source-highlight"
                aria-hidden
                className="pointer-events-none absolute rounded-[2px] bg-accent/20 outline-2 outline-accent"
                style={{
                  left: `${region[0] * 100}%`,
                  top: `${region[1] * 100}%`,
                  width: `${(region[2] - region[0]) * 100}%`,
                  height: `${(region[3] - region[1]) * 100}%`,
                }}
              />
            ) : null}
          </div>
        )}
      </div>
    </Panel>
  );
}

function FindingList({ findings, documents }: { findings: Finding[]; documents: DocumentReference[] }) {
  if (findings.length === 0) {
    return null;
  }
  return (
    <div>
      <SectionTitle>To check</SectionTitle>
      <Panel>
        <ul className="divide-y divide-line">
          {findings.map((finding, index) => (
            <li key={`${finding.code}-${index}`} className="flex gap-2.5 px-4 py-3 text-[13px]">
              <TriangleAlert aria-hidden className={`mt-0.5 size-4 shrink-0 ${finding.severity === "INFO" ? "text-ink-faint" : "text-caution"}`} />
              <span>{findingMessage(finding, documents)}</span>
            </li>
          ))}
        </ul>
      </Panel>
    </div>
  );
}

function LineItems({
  items,
  currency,
  selectedKey,
  onSelect,
}: {
  items: LineItem[];
  currency: string;
  selectedKey: string | null;
  onSelect: (item: LineItem, key: string) => void;
}) {
  return (
    <ul className="divide-y divide-line border-t border-line">
      {items.map((item) => {
        const key = `line-${item.documentId}-${item.position}`;
        return (
          <li key={key}>
            <button
              type="button"
              aria-pressed={selectedKey === key}
              onClick={() => onSelect(item, key)}
              className={`flex w-full items-start justify-between gap-3 px-4 py-2.5 text-left text-[13px] transition-colors ${
                selectedKey === key ? "bg-accent-soft" : "hover:bg-subtle"
              }`}
            >
              <span className="min-w-0 break-words">{item.description}</span>
              <span className="shrink-0 tabular-nums">
                {item.amount ? formatMoney(item.amount, currency) : <Badge tone="caution">Uncertain</Badge>}
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

const ATTENTION_ORDER: Record<FactState, number> = { CONFLICTING: 0, UNCERTAIN: 1, MISSING: 2, PRESENT: 3 };

/** Facts on one side, their evidence on the other: nothing here is taken on trust. */
export function FactReview({ facts, currency }: { facts: ClaimFacts; currency: string }) {
  const [selection, setSelection] = useState<Selection | null>(null);
  const [selectedKind, setSelectedKind] = useState<ClaimFact["kind"]>();
  const sourcePane = useRef<HTMLDivElement>(null);

  function select(next: Selection, kind: ClaimFact["kind"]) {
    setSelection(next);
    setSelectedKind(kind);
    // On a phone the source sits below the list, out of view.
    if (window.matchMedia("(max-width: 1023px)").matches) {
      sourcePane.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }

  function selectFact(fact: ClaimFact) {
    select({ key: fact.id, title: fieldLabel(fact.name), sources: fact.sources }, fact.kind);
  }

  const claimFacts = facts.facts
    .filter((fact) => fact.documentId === null)
    .sort((first, second) => ATTENTION_ORDER[first.state] - ATTENTION_ORDER[second.state]);

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <div className="min-w-0 space-y-8">
        <FindingList findings={facts.findings} documents={facts.documents} />
        <div>
          <SectionTitle>Claim facts</SectionTitle>
          <Panel>
            <div className="divide-y divide-line">
              {claimFacts.map((fact) => (
                <FactRow key={fact.id} fact={fact} currency={currency} selected={selection?.key === fact.id} onSelect={() => selectFact(fact)} />
              ))}
            </div>
          </Panel>
        </div>
        {facts.documents.map((document) => {
          const documentFacts = facts.facts.filter((fact) => fact.documentId === document.id);
          const items = facts.lineItems.filter((item) => item.documentId === document.id);
          if (documentFacts.length === 0 && items.length === 0) {
            return null;
          }
          const type = document.classifiedType ?? document.documentType;
          return (
            <div key={document.id}>
              <SectionTitle>
                {document.originalFilename} · {DOCUMENT_TYPE_LABELS[type]}
              </SectionTitle>
              <Panel>
                <div className="divide-y divide-line">
                  {documentFacts.map((fact) => (
                    <FactRow key={fact.id} fact={fact} currency={currency} selected={selection?.key === fact.id} onSelect={() => selectFact(fact)} />
                  ))}
                </div>
                {items.length > 0 ? (
                  <LineItems
                    items={items}
                    currency={currency}
                    selectedKey={selection?.key ?? null}
                    onSelect={(item, key) => select({ key, title: item.description, sources: [lineItemSource(item)] }, "AMOUNT")}
                  />
                ) : null}
              </Panel>
            </div>
          );
        })}
      </div>
      <div ref={sourcePane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
        <SectionTitle>Source</SectionTitle>
        {/* Keyed so that moving to another fact starts at its first source. */}
        <SourceView key={selection?.key ?? "none"} selection={selection} documents={facts.documents} currency={currency} kind={selectedKind} />
      </div>
    </div>
  );
}
