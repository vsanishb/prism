"use client";

import { CircleAlert, FileSearch, Search, SearchX } from "lucide-react";
import { useRef, useState, type FormEvent } from "react";
import { SourcePage } from "@/components/source-page";
import { Button } from "@/components/ui/button";
import { Badge, EmptyState, Notice, Skeleton } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { DEGRADED_LABELS, INCOMING_RELATION_LABELS, PROVISION_TYPE_LABELS, RELATION_LABELS } from "@/lib/labels";
import type { PolicySearchResult, ProvisionType, RelatedLink, SearchResultItem, StageScore } from "@/lib/types";

const TYPES: ProvisionType[] = ["SCHEDULE", "DEFINITION", "BENEFIT", "WAITING_PERIOD", "EXCLUSION", "PROCEDURE", "CLAUSE"];
const MAX_QUERY = 500;
type Tab = "results" | "compare";

interface Source {
  id: string;
  title: string;
  page: number;
  region: SearchResultItem["region"];
}

interface Named {
  key: string | null;
  title: string;
}

function name(provision: Named): string {
  return provision.key ? `${provision.key} ${provision.title}` : provision.title;
}

function pages(start: number, end: number): string {
  return start === end ? `Page ${start}` : `Pages ${start} to ${end}`;
}

function rank(score: StageScore | null): string {
  return score ? `#${score.rank}` : "–";
}

function relationLabel(link: RelatedLink): string {
  return link.direction === "OUTGOING" ? RELATION_LABELS[link.relation] : INCOMING_RELATION_LABELS[link.relation];
}

function ResultCard({
  item,
  result,
  selected,
  onOpen,
}: {
  item: SearchResultItem;
  result: PolicySearchResult;
  selected: string | null;
  onOpen: (source: Source) => void;
}) {
  const related = new Map(result.related.map((provision) => [provision.provisionId, provision]));
  return (
    <li className={selected === item.provisionId ? "bg-accent-soft/50" : undefined}>
      <button
        type="button"
        aria-pressed={selected === item.provisionId}
        onClick={() => onOpen({ id: item.provisionId, title: name(item), page: item.pageStart, region: item.region })}
        className="block w-full px-4 pt-3.5 pb-2 text-left hover:bg-subtle"
      >
        <div className="flex items-start gap-3">
          <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-subtle text-xs font-semibold text-ink-muted">
            {item.finalRank}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="font-medium">{name(item)}</span>
              <Badge>{PROVISION_TYPE_LABELS[item.type]}</Badge>
              {item.exact === "CLAUSE" ? <Badge tone="accent">Named in your search</Badge> : null}
              {item.exact === "TERM" ? <Badge tone="accent">Defined term</Badge> : null}
            </div>
            <p className="mt-0.5 text-xs text-ink-muted">
              {[...item.sectionPath, pages(item.pageStart, item.pageEnd)].join(" · ")}
              {item.parts > 1 ? ` · Part ${item.part} of ${item.parts}` : ""}
            </p>
            <p className="mt-1.5 line-clamp-4 text-[13px] text-ink">{item.text}</p>
            <p className="mt-1.5 text-xs text-ink-muted" data-testid="stage-ranks">
              Meaning {rank(item.semantic)} · Words {rank(item.lexical)} · Combined {rank(item.fused)}
              {item.rerank ? ` · Reranked ${rank(item.rerank)}` : ""}
            </p>
          </div>
        </div>
      </button>
      {item.related.length > 0 ? (
        <ul className="space-y-1 px-4 pb-3 pl-13" aria-label={`Related to ${name(item)}`}>
          {item.related.map((link) => {
            const provision = related.get(link.provisionId);
            if (!provision) {
              return null;
            }
            return (
              <li key={`${link.provisionId}-${link.relation}-${link.direction}`}>
                <button
                  type="button"
                  onClick={() =>
                    onOpen({ id: provision.provisionId, title: name(provision), page: provision.pageStart, region: provision.region })
                  }
                  className="text-left text-[13px] text-ink-muted hover:text-ink"
                >
                  <span>{relationLabel(link)} </span>
                  <span className="font-medium text-accent underline-offset-2 hover:underline">{name(provision)}</span>
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </li>
  );
}

/** Each provision found by any stage, with its rank in each: how the final list came about. */
function Comparison({ result }: { result: PolicySearchResult }) {
  const rows = new Map<string, { named: Named; meaning?: number; words?: number; final?: number }>();
  for (const [list, field] of [
    [result.semantic, "meaning"],
    [result.lexical, "words"],
  ] as const) {
    for (const hit of list) {
      const row = rows.get(hit.provisionId) ?? { named: hit };
      row[field] = hit.rank;
      rows.set(hit.provisionId, row);
    }
  }
  for (const item of result.results) {
    const row = rows.get(item.provisionId) ?? { named: item };
    row.final = item.finalRank;
    rows.set(item.provisionId, row);
  }
  const ordered = [...rows.values()].sort(
    (a, b) => (a.final ?? 99) - (b.final ?? 99) || (a.meaning ?? 99) - (b.meaning ?? 99) || (a.words ?? 99) - (b.words ?? 99),
  );
  return (
    <Panel className="overflow-x-auto">
      <table className="w-full text-[13px]">
        <caption className="px-4 pt-3 pb-2 text-left text-xs text-ink-muted">
          Top ten from each stage. Meaning compares embeddings; Words is full-text search. The final list fuses both, adds named clauses
          and terms, then reranks.
        </caption>
        <thead>
          <tr className="border-y border-line text-left text-xs text-ink-muted">
            <th scope="col" className="px-4 py-2 font-medium">
              Provision
            </th>
            <th scope="col" className="px-3 py-2 text-right font-medium">
              Meaning
            </th>
            <th scope="col" className="px-3 py-2 text-right font-medium">
              Words
            </th>
            <th scope="col" className="px-4 py-2 text-right font-medium">
              Final
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {ordered.map((row) => (
            <tr key={name(row.named)}>
              <td className="px-4 py-2">{name(row.named)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{row.meaning ? `#${row.meaning}` : "–"}</td>
              <td className="px-3 py-2 text-right tabular-nums">{row.words ? `#${row.words}` : "–"}</td>
              <td className="px-4 py-2 text-right font-medium tabular-nums">{row.final ? `#${row.final}` : "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}

/** Search over one published version, showing how each stage ranked what it found (M5). */
export function RetrievalExplorer({ policyId, versionId, documentId }: { policyId: string; versionId: string; documentId: string }) {
  const [query, setQuery] = useState("");
  const [types, setTypes] = useState<ProvisionType[]>([]);
  const [result, setResult] = useState<PolicySearchResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [queryError, setQueryError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("results");
  const [source, setSource] = useState<Source | null>(null);
  const sourcePane = useRef<HTMLDivElement>(null);

  function toggle(type: ProvisionType) {
    setTypes((current) => (current.includes(type) ? current.filter((item) => item !== type) : [...current, type]));
  }

  function open(next: Source) {
    setSource(next);
    if (window.matchMedia("(max-width: 1023px)").matches) {
      requestAnimationFrame(() => sourcePane.current?.scrollIntoView({ block: "start" }));
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const text = query.trim();
    if (text.length < 2) {
      setQueryError("Enter at least two characters.");
      return;
    }
    setQueryError(null);
    setError(null);
    setBusy(true);
    try {
      const found = await apiRequest<PolicySearchResult>("POST", `/api/v1/policies/${policyId}/versions/${versionId}/search`, {
        query: text,
        types,
      });
      setResult(found);
      setTab("results");
      const top = found.results[0];
      setSource(top ? { id: top.provisionId, title: name(top), page: top.pageStart, region: top.region } : null);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Panel className="mb-6 p-5">
        <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4" role="search">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="min-w-0 flex-1">
              <Field
                label="Question or words"
                htmlFor="search-query"
                error={queryError ?? undefined}
                hint="A clause number, a defined term, or a question such as “waiting period for cataract surgery”."
              >
                <Input
                  id="search-query"
                  value={query}
                  maxLength={MAX_QUERY}
                  autoComplete="off"
                  onChange={(event) => setQuery(event.target.value)}
                />
              </Field>
            </div>
            <Button type="submit" loading={busy} className="sm:mb-[22px]">
              {busy ? null : <Search aria-hidden className="size-4" />}
              Search
            </Button>
          </div>
          <fieldset>
            <legend className="mb-1.5 text-[13px] font-medium">Only these kinds of provision</legend>
            <div className="flex flex-wrap gap-1.5">
              {TYPES.map((type) => {
                const on = types.includes(type);
                return (
                  <label
                    key={type}
                    className={`cursor-pointer rounded-full border px-2.5 py-1 text-xs font-medium has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-accent ${on ? "border-accent bg-accent-soft text-accent" : "border-line-strong text-ink-muted hover:text-ink"}`}
                  >
                    <input type="checkbox" className="sr-only" checked={on} onChange={() => toggle(type)} />
                    {PROVISION_TYPE_LABELS[type]}
                  </label>
                );
              })}
            </div>
            <p className="mt-1.5 text-xs text-ink-muted">{types.length === 0 ? "All kinds are searched." : `${types.length} selected.`}</p>
          </fieldset>
        </form>
      </Panel>

      {error ? (
        <div className="mb-6">
          <Notice tone="critical" title={error} />
        </div>
      ) : null}

      {busy ? (
        <div className="space-y-3" aria-label="Searching">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-24" />
          ))}
        </div>
      ) : result === null ? (
        <Panel>
          <EmptyState icon={FileSearch} title="Search this version">
            Results come only from this published version. Each one shows where it ranked in each stage, and the provisions it depends on.
          </EmptyState>
        </Panel>
      ) : (
        <>
          {result.degraded.length > 0 ? (
            <div className="mb-4 flex gap-2.5 rounded-control bg-caution-soft px-3 py-2.5 text-[13px] text-caution" role="status">
              <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
              <div>
                <p className="font-medium">Partial search</p>
                {result.degraded.map((reason) => (
                  <p key={reason} className="text-ink-muted">
                    {DEGRADED_LABELS[reason]}
                  </p>
                ))}
              </div>
            </div>
          ) : null}
          <div role="tablist" aria-label="Search views" className="mb-4 flex gap-1 border-b border-line">
            {(
              [
                ["results", `Results (${result.results.length})`],
                ["compare", "Compare stages"],
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
              {tab === "results" ? (
                result.results.length === 0 ? (
                  <Panel>
                    <EmptyState icon={SearchX} title="No provisions matched">
                      Try other words, a clause number, or fewer kinds of provision.
                    </EmptyState>
                  </Panel>
                ) : (
                  <Panel>
                    <ol className="divide-y divide-line">
                      {result.results.map((item) => (
                        <ResultCard key={item.provisionId} item={item} result={result} selected={source?.id ?? null} onOpen={open} />
                      ))}
                    </ol>
                  </Panel>
                )
              ) : (
                <Comparison result={result} />
              )}
              <p className="mt-3 text-xs text-ink-muted">
                Embeddings: {result.embeddingModel}. Reranking: {result.rerankModel ?? "not applied"}.
              </p>
            </div>
            <div ref={sourcePane} className="min-w-0 scroll-mt-4 lg:sticky lg:top-6 lg:self-start">
              <SectionTitle>Source</SectionTitle>
              {source ? (
                <>
                  <p className="mb-2 text-[13px] font-medium">
                    {source.title} · Page {source.page}
                  </p>
                  <SourcePage documentId={documentId} page={source.page} region={source.region} label={`${source.title}, page ${source.page}`} />
                </>
              ) : (
                <Panel>
                  <EmptyState icon={FileSearch} title="Choose a result">
                    Its page of the wording opens here.
                  </EmptyState>
                </Panel>
              )}
            </div>
          </div>
        </>
      )}
    </>
  );
}
