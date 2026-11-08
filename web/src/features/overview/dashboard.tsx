import { ArrowRight, FileStack } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { Badge, EmptyState } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { ClaimStatusBadge } from "@/features/claims/claim-status";
import { BAND_TONES } from "@/features/risk/risk-format";
import { formatDate, formatMoney } from "@/lib/format";
import { DECISION_LABELS, QUEUE_LABELS, RISK_BAND_LABELS } from "@/lib/labels";
import type { Decision, MoneyTotal, Overview, ReviewQueue, RiskBand } from "@/lib/types";

const OPEN_STATUSES = ["SUBMITTED", "PROCESSING", "AWAITING_INFORMATION", "IN_REVIEW", "ESCALATED"] as const;
const BANDS: RiskBand[] = ["LOW", "MEDIUM", "HIGH"];
const QUEUES: ReviewQueue[] = ["REVIEW", "SENIOR_REVIEW", "INVESTIGATION"];
const DECISIONS: Decision[] = ["APPROVED", "PARTIALLY_APPROVED", "DENIED"];
const BAR_COLORS: Record<RiskBand, string> = { LOW: "bg-positive", MEDIUM: "bg-caution", HIGH: "bg-critical" };

function total(values: Partial<Record<string, number>>): number {
  return Object.values(values).reduce<number>((sum, value) => sum + (value ?? 0), 0);
}

function money(totals: MoneyTotal[]): string {
  return totals.length === 0 ? formatMoney("0", "INR") : totals.map((item) => formatMoney(item.amount, item.currency)).join(" + ");
}

function Figure({ label, value, detail, testId }: { label: string; value: number; detail: ReactNode; testId: string }) {
  return (
    <Panel className="px-5 py-5">
      <p className="text-sm font-medium text-ink-muted">{label}</p>
      <p className="mt-2 text-[2.25rem] leading-none font-semibold tracking-tight tabular-nums" data-testid={testId}>
        {value}
      </p>
      <p className="mt-2.5 text-xs text-ink-muted">{detail}</p>
    </Panel>
  );
}

function Card({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Panel className="p-5">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="font-semibold">{title}</h2>
        {action}
      </div>
      {children}
    </Panel>
  );
}

function MoreLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="inline-flex items-center gap-1 text-sm font-medium text-accent hover:text-accent-hover">
      {children}
      <ArrowRight aria-hidden className="size-3.5" />
    </Link>
  );
}

/** Proportions of screened claims by band, as a single bar and a legend with counts. */
function RiskBands({ bands }: { bands: Overview["riskBands"] }) {
  const screened = total(bands);
  if (screened === 0) {
    return <p className="text-sm text-ink-muted">No claim has been screened yet.</p>;
  }
  return (
    <div>
      <div className="flex h-2.5 overflow-hidden rounded-full bg-subtle" role="img" aria-label="Screened claims by risk band">
        {BANDS.map((band) =>
          bands[band] ? <div key={band} className={BAR_COLORS[band]} style={{ width: `${((bands[band] ?? 0) / screened) * 100}%` }} /> : null,
        )}
      </div>
      <ul className="mt-4 space-y-2.5">
        {BANDS.map((band) => (
          <li key={band} className="flex items-center justify-between text-sm">
            <span className="flex items-center gap-2.5">
              <span aria-hidden className={`size-2.5 rounded-full ${BAR_COLORS[band]}`} />
              {RISK_BAND_LABELS[band]}
            </span>
            <span className="font-medium tabular-nums" data-testid={`band-${band}`}>
              {bands[band] ?? 0}
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-4 text-xs text-ink-muted">
        Latest screening of {screened} claim{screened === 1 ? "" : "s"}. A band sets how closely a claim is reviewed. It never decides it.
      </p>
    </div>
  );
}

function Counts<K extends string>({ keys, counts, labels }: { keys: K[]; counts: Partial<Record<K, number>>; labels: Record<K, string> }) {
  return (
    <dl className="divide-y divide-line">
      {keys.map((key) => (
        <div key={key} className="flex items-center justify-between py-2.5 text-sm first:pt-0 last:pb-0">
          <dt className="text-ink-muted">{labels[key]}</dt>
          <dd className="font-medium tabular-nums">{counts[key] ?? 0}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Dashboard({ overview, canReview }: { overview: Overview; canReview: boolean }) {
  const status = overview.claimsByStatus;
  const open = OPEN_STATUSES.reduce((sum, key) => sum + (status[key] ?? 0), 0);
  const inReview = (status.IN_REVIEW ?? 0) + (status.ESCALATED ?? 0);
  const decided = total(overview.outcomes);

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Figure label="Open claims" value={open} testId="figure-open" detail={<>{money(overview.openClaimed)} claimed</>} />
        <Figure
          label="With reviewers"
          value={inReview}
          testId="figure-review"
          detail={<>{status.ESCALATED ?? 0} escalated or under investigation</>}
        />
        <Figure
          label="Waiting for information"
          value={status.AWAITING_INFORMATION ?? 0}
          testId="figure-information"
          detail="Documents requested from the claimant"
        />
        <Figure label="Decided" value={decided} testId="figure-decided" detail={<>{money(overview.approved)} approved</>} />
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.75fr)_minmax(0,1fr)]">
        <Panel>
          <div className="flex items-center justify-between gap-3 px-5 pt-5 pb-4">
            <h2 className="font-semibold">Recent claims</h2>
            <MoreLink href="/claims">All claims</MoreLink>
          </div>
          {overview.recent.length === 0 ? (
            <EmptyState icon={FileStack} title="No claims yet">
              Claims appear here once they are created.
            </EmptyState>
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Claim</Th>
                  <Th>Status</Th>
                  <Th>Risk</Th>
                  <Th className="text-right">Claimed</Th>
                </tr>
              </thead>
              <tbody>
                {overview.recent.map((claim) => (
                  <tr key={claim.id} className="last:[&>td]:border-b-0">
                    <Td>
                      <Link href={`/claims/${claim.id}`} className="font-medium whitespace-nowrap hover:text-accent">
                        {claim.claimNumber}
                      </Link>
                      <p className="text-xs text-ink-muted" title={`Admitted ${formatDate(claim.admissionDate)}`}>
                        {claim.patientName}
                      </p>
                    </Td>
                    <Td>
                      <ClaimStatusBadge status={claim.status} />
                    </Td>
                    <Td>
                      {claim.riskBand ? (
                        <Badge tone={BAND_TONES[claim.riskBand]}>{RISK_BAND_LABELS[claim.riskBand]}</Badge>
                      ) : (
                        <span className="text-xs text-ink-faint">Not screened</span>
                      )}
                    </Td>
                    <Td className="text-right whitespace-nowrap tabular-nums">{formatMoney(claim.claimedAmount, claim.currency)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>

        <div className="space-y-6">
          <Card title="Risk screening">
            <RiskBands bands={overview.riskBands} />
          </Card>
          <Card title="Review work" action={canReview ? <MoreLink href="/review">Open queues</MoreLink> : undefined}>
            <Counts keys={QUEUES} counts={overview.openTasks} labels={QUEUE_LABELS} />
          </Card>
          <Card title="Outcomes">
            <Counts keys={DECISIONS} counts={overview.outcomes} labels={DECISION_LABELS} />
          </Card>
        </div>
      </div>
    </div>
  );
}
