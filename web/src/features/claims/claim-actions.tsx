"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Notice } from "@/components/ui/feedback";
import { Field, Select } from "@/components/ui/field";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { WITHDRAWAL_REASON_LABELS } from "@/lib/labels";
import type { ClaimDetail, WithdrawalReason } from "@/lib/types";

export function ClaimActions({ claim }: { claim: ClaimDetail }) {
  const router = useRouter();
  const [dialog, setDialog] = useState<"submit" | "withdraw" | null>(null);
  const [reason, setReason] = useState<WithdrawalReason | "">("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const base = `/api/v1/claims/${claim.summary.id}`;
  const actions = claim.availableActions;

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      setDialog(null);
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  if (!actions.includes("EDIT") && !actions.includes("SUBMIT") && !actions.includes("WITHDRAW")) {
    return null;
  }

  return (
    <div className="flex items-center gap-2">
      {actions.includes("WITHDRAW") ? (
        <Button variant="ghost" onClick={() => setDialog("withdraw")}>
          Withdraw
        </Button>
      ) : null}
      {actions.includes("EDIT") ? (
        <Link
          href={`/claims/${claim.summary.id}/edit`}
          className="inline-flex h-9 items-center rounded-control border border-line-strong bg-surface px-3.5 text-sm font-medium transition-colors hover:bg-subtle"
        >
          Edit
        </Link>
      ) : null}
      {actions.includes("SUBMIT") ? (
        <Button disabled={claim.submissionIssues.length > 0} onClick={() => setDialog("submit")}>
          Submit claim
        </Button>
      ) : null}

      <Dialog
        open={dialog === "submit"}
        onOpenChange={(open) => !open && setDialog(null)}
        title="Submit this claim?"
        description="The claim details will be locked, and the policy version will be fixed from the admission date."
      >
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setDialog(null)}>
              Cancel
            </Button>
            <Button loading={busy} onClick={() => run(() => apiRequest("POST", `${base}/submission`))}>
              Confirm submission
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={dialog === "withdraw"}
        onOpenChange={(open) => !open && setDialog(null)}
        title="Withdraw this claim?"
        description="A withdrawn claim is closed for good, and stays on record."
      >
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <Field label="Reason" htmlFor="withdrawal-reason">
            <Select id="withdrawal-reason" value={reason} onChange={(event) => setReason(event.target.value as WithdrawalReason)}>
              <option value="">Choose a reason</option>
              {Object.entries(WITHDRAWAL_REASON_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </Select>
          </Field>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setDialog(null)}>
              Cancel
            </Button>
            <Button variant="danger" loading={busy} disabled={!reason} onClick={() => run(() => apiRequest("POST", `${base}/withdrawal`, { reason }))}>
              Withdraw claim
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
