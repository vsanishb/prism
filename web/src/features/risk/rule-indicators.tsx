import { CircleCheck, ExternalLink, FileText, ListChecks } from "lucide-react";
import Link from "next/link";
import { Badge, EmptyState } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { INDICATOR_KIND_DESCRIPTIONS, INDICATOR_KIND_LABELS, RISK_CATEGORY_LABELS, RISK_SEVERITY_LABELS } from "@/lib/labels";
import type { IndicatorKind, RiskEvidence, RiskIndicator, RiskRule, RiskSeverity } from "@/lib/types";

const CATEGORIES = ["CONSISTENCY", "FINANCIAL", "DUPLICATE", "DOCUMENT", "MISSING_INFORMATION"] as const;

const KIND_TONES: Record<IndicatorKind, "neutral" | "caution" | "critical"> = {
  DATA_INCONSISTENCY: "neutral",
  RISK_INDICATOR: "caution",
  FRAUD_INDICATOR: "critical",
};

const SEVERITY_TONES: Record<RiskSeverity, "neutral" | "caution" | "critical"> = {
  LOW: "neutral",
  MEDIUM: "caution",
  HIGH: "critical",
};

export interface EvidenceSource {
  key: string;
  documentId: string;
  page: number;
  region: number[] | null;
  label: string;
}

function EvidenceItem({
  evidence,
  sourceKey,
  selected,
  onOpen,
}: {
  evidence: RiskEvidence;
  sourceKey: string;
  selected: boolean;
  onOpen: (source: EvidenceSource) => void;
}) {
  const body = (
    <>
      <span className="font-medium">{evidence.label}</span>
      {evidence.detail ? <span className="text-ink-muted">: {evidence.detail}</span> : null}
    </>
  );
  if (evidence.matchedClaim) {
    return (
      <li className="text-[13px]">
        <Link href={`/claims/${evidence.matchedClaim.id}`} className="inline-flex items-center gap-1 text-accent hover:text-accent-hover">
          <ExternalLink aria-hidden className="size-3.5" />
          Other claim {evidence.matchedClaim.claimNumber}
        </Link>
        <span className="text-ink-muted"> (opens only if you may see it)</span>
      </li>
    );
  }
  if (evidence.documentId) {
    const documentId = evidence.documentId;
    return (
      <li>
        <button
          type="button"
          aria-pressed={selected}
          onClick={() =>
            onOpen({ key: sourceKey, documentId, page: evidence.page ?? 1, region: evidence.region, label: evidence.label })
          }
          className={`flex w-full items-start gap-1.5 rounded-control border px-2.5 py-1.5 text-left text-[13px] hover:bg-subtle ${
            selected ? "border-accent bg-accent-soft/40" : "border-line"
          }`}
        >
          <FileText aria-hidden className="mt-0.5 size-3.5 shrink-0 text-accent" />
          <span className="min-w-0 break-words">{body}</span>
        </button>
      </li>
    );
  }
  return <li className="px-2.5 text-[13px] break-words">{body}</li>;
}

function IndicatorCard({
  indicator,
  selected,
  onOpen,
}: {
  indicator: RiskIndicator;
  selected: string | null;
  onOpen: (source: EvidenceSource) => void;
}) {
  return (
    <li id={indicator.signalId ? `signal-${indicator.signalId}` : undefined} className="scroll-mt-6 p-5" data-testid="risk-indicator">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h4 className="min-w-0 font-medium">
          {indicator.signalId ? <span className="mr-1.5 text-xs font-medium text-accent">{indicator.signalId}</span> : null}
          {indicator.title}
        </h4>
        <span className="shrink-0 text-xs text-ink-muted tabular-nums">+{indicator.points} points</span>
      </div>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        <span title={INDICATOR_KIND_DESCRIPTIONS[indicator.kind]}>
          <Badge tone={KIND_TONES[indicator.kind]}>{INDICATOR_KIND_LABELS[indicator.kind]}</Badge>
        </span>
        <Badge tone={SEVERITY_TONES[indicator.severity]}>{RISK_SEVERITY_LABELS[indicator.severity]} severity</Badge>
      </div>
      <p className="mt-2 text-[13px]">{indicator.explanation}</p>
      {indicator.kind === "FRAUD_INDICATOR" ? (
        <p className="mt-1 text-xs text-ink-muted">{INDICATOR_KIND_DESCRIPTIONS.FRAUD_INDICATOR}</p>
      ) : null}
      {indicator.evidence.length > 0 ? (
        <>
          <p className="mt-3 mb-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">Evidence</p>
          <ul className="space-y-1.5">
            {indicator.evidence.map((evidence, index) => {
              const key = `${indicator.position}-${index}`;
              return <EvidenceItem key={key} evidence={evidence} sourceKey={key} selected={selected === key} onOpen={onOpen} />;
            })}
          </ul>
        </>
      ) : null}
      <p className="mt-3 text-xs text-ink-faint">
        Rule {indicator.ruleId}, version {indicator.ruleVersion}
      </p>
    </li>
  );
}

/** Indicators found by deterministic rules, by category. Each one rests on the evidence listed under it. */
export function RuleIndicators({
  indicators,
  selected,
  onOpen,
}: {
  indicators: RiskIndicator[];
  selected: string | null;
  onOpen: (source: EvidenceSource) => void;
}) {
  if (indicators.length === 0) {
    return (
      <Panel>
        <EmptyState icon={CircleCheck} title="No rule raised an indicator">
          Every consistency, financial, duplicate, document, and missing-information check passed. The checks are listed below.
        </EmptyState>
      </Panel>
    );
  }
  return (
    <div className="space-y-4">
      {CATEGORIES.map((category) => {
        const found = indicators.filter((indicator) => indicator.category === category);
        if (found.length === 0) {
          return null;
        }
        return (
          <section key={category}>
            <h3 className="mb-2 text-[13px] font-medium">{RISK_CATEGORY_LABELS[category]}</h3>
            <Panel>
              <ol className="divide-y divide-line">
                {found.map((indicator) => (
                  <IndicatorCard key={indicator.position} indicator={indicator} selected={selected} onOpen={onOpen} />
                ))}
              </ol>
            </Panel>
          </section>
        );
      })}
    </div>
  );
}

/** Every rule that ran, so a quiet result shows what was checked rather than nothing. */
export function RuleChecklist({ rules, open }: { rules: RiskRule[]; open: boolean }) {
  const passed = rules.filter((rule) => !rule.triggered).length;
  return (
    <details open={open} className="group rounded-panel border border-line bg-surface">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-3.5 text-[13px] font-medium">
        <span className="flex items-center gap-2">
          <ListChecks aria-hidden className="size-4 text-ink-muted" />
          Every rule checked ({passed} of {rules.length} found nothing)
        </span>
        <span className="text-xs text-ink-muted group-open:hidden">Show</span>
        <span className="hidden text-xs text-ink-muted group-open:inline">Hide</span>
      </summary>
      <div className="border-t border-line px-5 py-4">
        {CATEGORIES.map((category) => (
          <div key={category} className="mb-4 last:mb-0">
            <p className="mb-1.5 text-xs font-medium tracking-wide text-ink-muted uppercase">{RISK_CATEGORY_LABELS[category]}</p>
            <ul className="space-y-1">
              {rules
                .filter((rule) => rule.category === category)
                .map((rule) => (
                  <li key={rule.id} className="flex items-start gap-2 text-[13px]" data-testid="risk-rule">
                    {rule.triggered ? (
                      <Badge tone="caution">Raised</Badge>
                    ) : (
                      <CircleCheck aria-label="Found nothing" className="mt-0.5 size-4 shrink-0 text-positive" />
                    )}
                    <span className="min-w-0">
                      {rule.checks}
                      <span className="ml-1.5 text-xs text-ink-faint">{rule.id}</span>
                    </span>
                  </li>
                ))}
            </ul>
          </div>
        ))}
      </div>
    </details>
  );
}
