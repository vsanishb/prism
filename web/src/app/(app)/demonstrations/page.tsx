import { FlaskConical } from "lucide-react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { EmptyState } from "@/components/ui/feedback";
import { AccessDenied, LoadFailed, PageHeader, Panel } from "@/components/ui/layout";
import { RiskDemonstrations } from "@/features/demonstrations/risk-demonstrations";
import { serverFetch } from "@/lib/server-api";
import type { DemonstrationScenario, RiskBand, RiskScreening } from "@/lib/types";

export const metadata: Metadata = { title: "Risk demonstrations" };

export default async function DemonstrationsPage() {
  const result = await serverFetch<DemonstrationScenario[]>("/api/v1/demonstrations/risk");
  if (!result.ok) {
    if (result.status === 404) {
      notFound();
    }
    return result.status === 403 ? <AccessDenied /> : <LoadFailed />;
  }
  const scenarios = result.data;
  const screenings = await Promise.all(
    scenarios
      .filter((scenario) => scenario.claim.status !== "DRAFT")
      .map(async (scenario) => {
        const risk = await serverFetch<{ screening: RiskScreening | null }>(`/api/v1/claims/${scenario.claim.id}/risk`);
        return [scenario.claim.id, risk.ok ? (risk.data.screening?.band ?? null) : null] as const;
      }),
  );
  const bands: Record<string, RiskBand> = {};
  for (const [id, band] of screenings) {
    if (band) {
      bands[id] = band;
    }
  }

  return (
    <>
      <PageHeader
        title="Risk demonstrations"
        description="Three prepared claims that exercise all of risk screening: rules, both anomaly detectors, scoring, and the AI summary. Submitting one runs it through the normal pipeline, with no upload needed."
      />
      <p className="mb-6 max-w-3xl text-sm text-ink-muted" role="note">
        Available only in the local environment. Each scenario runs once per database: reset the database to run it again.
        Results are screening signals for a reviewer. None of them is a finding of fraud.
      </p>
      {scenarios.length === 0 ? (
        <Panel>
          <EmptyState icon={FlaskConical} title="No demonstrations seeded">
            Reset the database to seed them: they are created with the seed data.
          </EmptyState>
        </Panel>
      ) : (
        <RiskDemonstrations scenarios={scenarios} bands={bands} />
      )}
    </>
  );
}
