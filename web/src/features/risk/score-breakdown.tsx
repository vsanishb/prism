import { Panel } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { RISK_CATEGORY_LABELS } from "@/lib/labels";
import type { RiskScreening } from "@/lib/types";

/** Each category's points, its cap, and what it contributed: the whole score, with nothing hidden. */
export function ScoreBreakdown({ screening }: { screening: RiskScreening }) {
  const total = screening.categories.reduce((sum, category) => sum + category.contribution, 0);
  return (
    <Panel>
      <div className="overflow-x-auto">
        <Table>
          <thead>
            <tr>
              <Th>Category</Th>
              <Th className="text-right">Points</Th>
              <Th className="text-right">Cap</Th>
              <Th className="text-right">Contributes</Th>
            </tr>
          </thead>
          <tbody>
            {screening.categories.map((category) => (
              <tr key={category.category} data-testid="score-category">
                <Td>{RISK_CATEGORY_LABELS[category.category]}</Td>
                <Td className="text-right tabular-nums">{category.points}</Td>
                <Td className="text-right text-ink-muted tabular-nums">{category.cap}</Td>
                <Td className={`text-right tabular-nums ${category.contribution > 0 ? "font-medium" : "text-ink-muted"}`}>
                  {category.contribution}
                  {category.points > category.cap ? <span className="ml-1 text-xs font-normal text-ink-muted">(capped)</span> : null}
                </Td>
              </tr>
            ))}
            <tr>
              <Td className="font-medium">Score</Td>
              <Td />
              <Td />
              <Td className="text-right font-medium tabular-nums">
                {screening.score}
                {total > screening.thresholds.maximum ? (
                  <span className="ml-1 text-xs font-normal text-ink-muted">(capped at {screening.thresholds.maximum})</span>
                ) : null}
              </Td>
            </tr>
          </tbody>
        </Table>
      </div>
      <p className="px-5 py-3 text-xs text-ink-muted">
        Low-severity indicators add 5 points, medium 12, and high 25. Each detector that flags the claim adds 10. Caps keep one noisy area
        from deciding the band, and the statistical cap is below the high threshold, so anomaly detection alone can never make a claim high
        risk.
      </p>
    </Panel>
  );
}
