import { Badge } from "@/components/ui/feedback";
import { CLAIM_STATUS_LABELS } from "@/lib/labels";
import type { ClaimStatus } from "@/lib/types";

const TONES = {
  DRAFT: "neutral",
  SUBMITTED: "accent",
  PROCESSING: "accent",
  AWAITING_INFORMATION: "caution",
  IN_REVIEW: "accent",
  ESCALATED: "caution",
  DECIDED: "positive",
  WITHDRAWN: "neutral",
} as const;

export function ClaimStatusBadge({ status }: { status: ClaimStatus }) {
  return <Badge tone={TONES[status]}>{CLAIM_STATUS_LABELS[status]}</Badge>;
}
