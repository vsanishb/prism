import { ClipboardCheck, Route } from "lucide-react";
import Link from "next/link";
import { Badge } from "@/components/ui/feedback";
import { Panel, SectionTitle } from "@/components/ui/layout";
import { formatDateTime, formatMoney } from "@/lib/format";
import { DECISION_LABELS, DOCUMENT_TYPE_LABELS, QUEUE_LABELS, TASK_REASON_LABELS } from "@/lib/labels";
import type { ReviewWorkspace } from "@/lib/types";

/** Where the claim is in review, on the claim page. Intake sees an open request for information here. */
export function ClaimReviewSummary({ claimId, review }: { claimId: string; review: ReviewWorkspace }) {
  const request = review.requests.find((candidate) => candidate.status === "OPEN");
  const outcome = review.outcomes.find((candidate) => candidate.current);
  return (
    <div>
      <SectionTitle>Review</SectionTitle>
      <Panel className="space-y-3 p-4 text-sm">
        {request ? (
          <div className="rounded-control bg-caution-soft px-3 py-2.5" role="status" data-testid="information-request">
            <p className="font-medium text-caution">Information requested</p>
            <p className="mt-0.5">
              {request.documentTypes.map((type) => DOCUMENT_TYPE_LABELS[type]).join(", ")}: “{request.message}”
            </p>
            <p className="mt-1 text-xs text-ink-muted">
              {request.requestedBy}, {formatDateTime(request.createdAt)}. Add the documents to this claim: processing resumes when they arrive.
            </p>
          </div>
        ) : null}
        {outcome ? (
          <div data-testid="claim-outcome">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={outcome.decision === "DENIED" ? "critical" : outcome.decision === "APPROVED" ? "positive" : "caution"}>
                {DECISION_LABELS[outcome.decision]}
              </Badge>
              {outcome.approvedAmount ? <span className="font-medium tabular-nums">{formatMoney(outcome.approvedAmount, outcome.currency)}</span> : null}
            </div>
            <p className="mt-1 text-xs text-ink-muted">
              By {outcome.decidedBy}, {formatDateTime(outcome.decidedAt)}
            </p>
          </div>
        ) : review.task ? (
          <p>
            {QUEUE_LABELS[review.task.queue]} · {TASK_REASON_LABELS[review.task.reason]}
            <span className="block text-xs text-ink-muted">
              {review.task.status === "CLAIMED" ? `Taken by ${review.task.assigneeName ?? "someone"}` : "Waiting to be taken"}
            </span>
          </p>
        ) : request ? null : (
          <p className="text-ink-muted">Not in review yet.</p>
        )}
        <div className="flex flex-col gap-1.5 border-t border-line pt-3">
          <Link href={`/claims/${claimId}/review`} className="inline-flex items-center gap-1.5 font-medium text-accent hover:text-accent-hover">
            <ClipboardCheck aria-hidden className="size-4" />
            Open review workspace
          </Link>
          <Link href={`/claims/${claimId}/evidence`} className="inline-flex items-center gap-1.5 font-medium text-accent hover:text-accent-hover">
            <Route aria-hidden className="size-4" />
            Evidence chain
          </Link>
        </div>
      </Panel>
    </div>
  );
}
