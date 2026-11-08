"use client";

import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Notice } from "@/components/ui/feedback";
import { Field, Input, Select, Textarea } from "@/components/ui/field";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime, formatMoney } from "@/lib/format";
import {
  AI_AGREEMENT_LABELS,
  DECISION_LABELS,
  DOCUMENT_TYPE_LABELS,
  FACT_NAME_LABELS,
  FINDING_LABELS,
  QUEUE_LABELS,
} from "@/lib/labels";
import type { AiAgreement, ClaimFact, Decision, InvestigationFinding, ReviewWorkspace } from "@/lib/types";

type DialogName = "request" | "escalate" | "finding" | "outcome" | "revise" | "correct" | null;

const REQUESTABLE = ["CLAIM_FORM", "HOSPITAL_BILL", "ITEMIZED_INVOICE", "DISCHARGE_SUMMARY", "PRESCRIPTION", "DIAGNOSTIC_REPORT", "OTHER"] as const;

function Actions({ children }: { children: ReactNode }) {
  return <div className="flex justify-end gap-2 pt-1">{children}</div>;
}

/** Shared by recording and revising an outcome: the same rules apply to both. */
function OutcomeForm({
  claimedAmount,
  currency,
  busy,
  error,
  submitLabel,
  onCancel,
  onSubmit,
}: {
  claimedAmount: string;
  currency: string;
  busy: boolean;
  error: string | null;
  submitLabel: string;
  onCancel: () => void;
  onSubmit: (body: { decision: Decision; approvedAmount: string | null; rationale: string; aiAgreement: AiAgreement }) => void;
}) {
  const [decision, setDecision] = useState<Decision | "">("");
  const [amount, setAmount] = useState("");
  const [rationale, setRationale] = useState("");
  const [agreement, setAgreement] = useState<AiAgreement | "">("");
  const ready = decision !== "" && agreement !== "" && rationale.trim().length >= 10 && (decision !== "PARTIALLY_APPROVED" || amount !== "");
  return (
    <div className="space-y-4">
      {error ? <Notice tone="critical" title={error} /> : null}
      <fieldset>
        <legend className="mb-1.5 text-sm font-medium">Decision</legend>
        <div className="space-y-1.5">
          {(Object.keys(DECISION_LABELS) as Decision[]).map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name="decision" value={value} checked={decision === value} onChange={() => setDecision(value)} />
              {DECISION_LABELS[value]}
              {value === "APPROVED" ? <span className="text-ink-muted">({formatMoney(claimedAmount, currency)})</span> : null}
            </label>
          ))}
        </div>
      </fieldset>
      {decision === "PARTIALLY_APPROVED" ? (
        <Field label="Approved amount" htmlFor="approved-amount" hint={`Less than the claimed ${formatMoney(claimedAmount, currency)}`}>
          <Input id="approved-amount" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} />
        </Field>
      ) : null}
      <Field label="Rationale" htmlFor="rationale" hint="Recorded with the outcome, and shown in the evidence chain.">
        <Textarea id="rationale" value={rationale} onChange={(event) => setRationale(event.target.value)} />
      </Field>
      <Field label="The AI analysis" htmlFor="ai-agreement" hint="Recorded to evaluate the analysis. Disagreeing is normal.">
        <Select id="ai-agreement" value={agreement} onChange={(event) => setAgreement(event.target.value as AiAgreement)}>
          <option value="">Choose</option>
          {(Object.keys(AI_AGREEMENT_LABELS) as AiAgreement[]).map((value) => (
            <option key={value} value={value}>
              {AI_AGREEMENT_LABELS[value]}
            </option>
          ))}
        </Select>
      </Field>
      <Actions>
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          loading={busy}
          disabled={!ready}
          onClick={() =>
            decision !== "" &&
            agreement !== "" &&
            onSubmit({ decision, approvedAmount: decision === "PARTIALLY_APPROVED" ? amount.replace(/,/g, "") : null, rationale, aiAgreement: agreement })
          }
        >
          {submitLabel}
        </Button>
      </Actions>
    </div>
  );
}

/**
 * The task's actions. The buttons follow what the server says this user may do; the server checks
 * every action again, including separation of duties.
 */
export function TaskActions({
  claimId,
  review,
  claimedAmount,
  currency,
  correcting,
  onCorrectDone,
}: {
  claimId: string;
  review: ReviewWorkspace;
  claimedAmount: string;
  currency: string;
  correcting: ClaimFact | null;
  onCorrectDone: () => void;
}) {
  const router = useRouter();
  const [dialog, setDialog] = useState<DialogName>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [types, setTypes] = useState<string[]>([]);
  const [message, setMessage] = useState("");
  const [target, setTarget] = useState("");
  const [note, setNote] = useState("");
  const [finding, setFinding] = useState<InvestigationFinding | "">("");
  const [rationale, setRationale] = useState("");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const base = `/api/v1/claims/${claimId}/review`;
  const taskId = review.task?.id;
  const can = (action: ReviewWorkspace["actions"][number]) => review.actions.includes(action);
  const openDialog: DialogName = correcting ? "correct" : dialog;

  function close() {
    setDialog(null);
    setError(null);
    onCorrectDone();
  }

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      setDialog(null);
      onCorrectDone();
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  const openRequest = review.requests.find((request) => request.status === "OPEN");

  return (
    <div className="flex flex-wrap gap-2">
      {error && openDialog === null ? (
        <div className="w-full">
          <Notice tone="critical" title={error} />
        </div>
      ) : null}
      {can("CLAIM_TASK") && review.task ? (
        <Button loading={busy} onClick={() => run(() => apiRequest("POST", `/api/v1/review/tasks/${review.task?.id}/claim`))}>
          Take task
        </Button>
      ) : null}
      {can("RECORD_OUTCOME") ? <Button onClick={() => setDialog("outcome")}>Record outcome</Button> : null}
      {can("RECORD_FINDING") ? <Button onClick={() => setDialog("finding")}>Record finding</Button> : null}
      {can("REVISE_OUTCOME") ? (
        <Button variant="secondary" onClick={() => setDialog("revise")}>
          Revise outcome
        </Button>
      ) : null}
      {can("REQUEST_REANALYSIS") ? (
        <Button variant="secondary" loading={busy} onClick={() => run(() => apiRequest("POST", `${base}/reanalysis`, { taskId }))}>
          Analyze again with corrections
        </Button>
      ) : null}
      {can("REQUEST_INFORMATION") ? (
        <Button variant="secondary" onClick={() => setDialog("request")}>
          Request information
        </Button>
      ) : null}
      {can("ESCALATE") ? (
        <Button
          variant="secondary"
          onClick={() => {
            // The targets arrive with the task, which may have been taken after this screen first rendered.
            setTarget(review.escalationTargets[0] ?? "");
            setDialog("escalate");
          }}
        >
          Escalate
        </Button>
      ) : null}
      {can("WITHDRAW_REQUEST") && openRequest ? (
        <Button
          variant="secondary"
          loading={busy}
          onClick={() => run(() => apiRequest("POST", `${base}/information-requests/${openRequest.id}/withdrawal`))}
        >
          Withdraw request
        </Button>
      ) : null}
      {can("RELEASE_TASK") && review.task ? (
        <Button variant="ghost" loading={busy} onClick={() => run(() => apiRequest("POST", `/api/v1/review/tasks/${review.task?.id}/release`))}>
          Release task
        </Button>
      ) : null}

      <Dialog open={openDialog === "outcome"} onOpenChange={(open) => !open && close()} title="Record the outcome" description="An authorized decision. It cannot be changed, only revised by a senior reviewer with a new record.">
        <OutcomeForm
          claimedAmount={claimedAmount}
          currency={currency}
          busy={busy}
          error={error}
          submitLabel="Record outcome"
          onCancel={close}
          onSubmit={(body) => run(() => apiRequest("POST", `${base}/outcome`, { taskId, ...body }))}
        />
      </Dialog>

      <Dialog open={openDialog === "revise"} onOpenChange={(open) => !open && close()} title="Revise the outcome" description="A new record supersedes the current one, which stays on record.">
        <OutcomeForm
          claimedAmount={claimedAmount}
          currency={currency}
          busy={busy}
          error={error}
          submitLabel="Record revision"
          onCancel={close}
          onSubmit={(body) => run(() => apiRequest("POST", `${base}/outcome/revision`, body))}
        />
      </Dialog>

      <Dialog open={openDialog === "request"} onOpenChange={(open) => !open && close()} title="Request information" description="The claim waits for intake to add the documents, then is processed again and returns to the queue.">
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <fieldset>
            <legend className="mb-1.5 text-sm font-medium">Documents needed</legend>
            <div className="grid grid-cols-2 gap-1.5">
              {REQUESTABLE.map((type) => (
                <label key={type} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={types.includes(type)}
                    onChange={(event) => setTypes(event.target.checked ? [...types, type] : types.filter((item) => item !== type))}
                  />
                  {DOCUMENT_TYPE_LABELS[type]}
                </label>
              ))}
            </div>
          </fieldset>
          <Field label="Message to intake" htmlFor="request-message">
            <Textarea id="request-message" value={message} onChange={(event) => setMessage(event.target.value)} />
          </Field>
          <Actions>
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
            <Button loading={busy} disabled={types.length === 0 || message.trim() === ""} onClick={() => run(() => apiRequest("POST", `${base}/information-requests`, { taskId, documentTypes: types, message }))}>
              Send request
            </Button>
          </Actions>
        </div>
      </Dialog>

      <Dialog open={openDialog === "escalate"} onOpenChange={(open) => !open && close()} title="Escalate" description="The task moves to a higher queue. You no longer hold it.">
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <Field label="To" htmlFor="escalation-target">
            <Select id="escalation-target" value={target} onChange={(event) => setTarget(event.target.value)}>
              {review.escalationTargets.map((queue) => (
                <option key={queue} value={queue}>
                  {QUEUE_LABELS[queue]}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Why" htmlFor="escalation-note" hint="At least 10 characters. The next person sees it first.">
            <Textarea id="escalation-note" value={note} onChange={(event) => setNote(event.target.value)} />
          </Field>
          <Actions>
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
            <Button loading={busy} disabled={note.trim().length < 10} onClick={() => run(() => apiRequest("POST", `${base}/escalation`, { taskId, target, note }))}>
              Escalate
            </Button>
          </Actions>
        </div>
      </Dialog>

      <Dialog open={openDialog === "finding"} onOpenChange={(open) => !open && close()} title="Record the investigation finding" description="Your finding, as the investigator. A reviewer then decides the claim.">
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <Field label="Finding" htmlFor="finding">
            <Select id="finding" value={finding} onChange={(event) => setFinding(event.target.value as InvestigationFinding)}>
              <option value="">Choose</option>
              {(Object.keys(FINDING_LABELS) as InvestigationFinding[]).map((item) => (
                <option key={item} value={item}>
                  {FINDING_LABELS[item]}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Rationale" htmlFor="finding-rationale" hint="What the investigation established, and how.">
            <Textarea id="finding-rationale" value={rationale} onChange={(event) => setRationale(event.target.value)} />
          </Field>
          <Actions>
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
            <Button loading={busy} disabled={finding === "" || rationale.trim().length < 10} onClick={() => run(() => apiRequest("POST", `${base}/investigation-findings`, { taskId, finding, rationale }))}>
              Record finding
            </Button>
          </Actions>
        </div>
      </Dialog>

      <Dialog
        open={openDialog === "correct"}
        onOpenChange={(open) => !open && close()}
        title={correcting ? `Correct ${(FACT_NAME_LABELS[correcting.name] ?? correcting.name).toLowerCase()}` : "Correct a fact"}
        description="A new version: what was read stays on record. It applies when you analyze the claim again."
      >
        <div className="space-y-4">
          {error ? <Notice tone="critical" title={error} /> : null}
          <p className="text-sm text-ink-muted">Current: {correcting?.value ?? "not established"}</p>
          <Field label="Corrected value" htmlFor="corrected-value" hint={correcting?.kind === "DATE" ? "As YYYY-MM-DD" : correcting?.kind === "AMOUNT" ? "A number, such as 98400.00" : undefined}>
            <Input id="corrected-value" value={value} onChange={(event) => setValue(event.target.value)} />
          </Field>
          <Field label="Why" htmlFor="correction-reason" hint="For example, what the source page shows.">
            <Textarea id="correction-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
          </Field>
          <Actions>
            <Button variant="ghost" onClick={close}>
              Cancel
            </Button>
            <Button
              loading={busy}
              disabled={value.trim() === "" || reason.trim().length < 5}
              onClick={() => run(() => apiRequest("POST", `${base}/fact-corrections`, { taskId, factId: correcting?.id, value, reason }))}
            >
              Save correction
            </Button>
          </Actions>
        </div>
      </Dialog>
    </div>
  );
}

/** Notes for the people reviewing this claim. Kept on record, never edited. */
export function NotesPanel({ claimId, review }: { claimId: string; review: ReviewWorkspace }) {
  const router = useRouter();
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest("POST", `/api/v1/claims/${claimId}/review/notes`, { body });
      setBody("");
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-panel border border-line bg-surface p-5">
      <h2 className="text-sm font-medium">Notes</h2>
      {review.notes.length === 0 ? <p className="mt-2 text-sm text-ink-muted">No notes yet.</p> : null}
      <ol className="mt-2 space-y-3">
        {review.notes.map((item) => (
          <li key={item.id} className="text-sm" data-testid="review-note">
            <p className="whitespace-pre-line">{item.body}</p>
            <p className="mt-0.5 text-xs text-ink-muted">
              {item.author}, {formatDateTime(item.createdAt)}
            </p>
          </li>
        ))}
      </ol>
      {review.actions.includes("ADD_NOTE") ? (
        <div className="mt-4 space-y-2">
          {error ? <Notice tone="critical" title={error} /> : null}
          <label htmlFor="new-note" className="sr-only">
            Add a note
          </label>
          <Textarea id="new-note" placeholder="Add a note for the next person" value={body} onChange={(event) => setBody(event.target.value)} />
          <div className="flex justify-end">
            <Button variant="secondary" loading={busy} disabled={body.trim() === ""} onClick={add}>
              Add note
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
