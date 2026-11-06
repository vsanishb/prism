"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/feedback";
import { Field, Input, Select } from "@/components/ui/field";
import { Panel } from "@/components/ui/layout";
import { ApiError, apiRequest, errorMessage } from "@/lib/client-api";
import { CLAIM_TYPE_LABELS } from "@/lib/labels";
import type { ClaimDetail, ClaimType, PolicySummary } from "@/lib/types";

interface FormValues {
  type: ClaimType;
  policyId: string;
  claimantName: string;
  patientName: string;
  hospitalName: string;
  admissionDate: string;
  dischargeDate: string;
  claimedAmount: string;
}

const EMPTY: FormValues = {
  type: "REIMBURSEMENT",
  policyId: "",
  claimantName: "",
  patientName: "",
  hospitalName: "",
  admissionDate: "",
  dischargeDate: "",
  claimedAmount: "",
};

function valuesFrom(claim: ClaimDetail): FormValues {
  return {
    type: claim.summary.type,
    policyId: claim.policy.id,
    claimantName: claim.claimantName,
    patientName: claim.summary.patientName,
    hospitalName: claim.summary.hospitalName,
    admissionDate: claim.summary.admissionDate,
    dischargeDate: claim.dischargeDate,
    claimedAmount: claim.summary.claimedAmount,
  };
}

/** Blank values become null, so that the server's own required-field messages apply. */
function toRequest(values: FormValues) {
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, value === "" ? null : value]));
}

export function ClaimForm({ policies, claim }: { policies: PolicySummary[]; claim?: ClaimDetail }) {
  const router = useRouter();
  const [values, setValues] = useState<FormValues>(claim ? valuesFrom(claim) : EMPTY);
  // One key for the life of this form, so that a retried submission cannot create two claims.
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  function set<K extends keyof FormValues>(key: K, value: FormValues[K]) {
    setValues((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      const saved = claim
        ? await apiRequest<ClaimDetail>("PUT", `/api/v1/claims/${claim.summary.id}`, {
            claim: toRequest(values),
            version: claim.version,
          })
        : await apiRequest<ClaimDetail>("POST", "/api/v1/claims", toRequest(values), { "Idempotency-Key": idempotencyKey });
      router.push(`/claims/${saved.summary.id}`);
      router.refresh();
    } catch (failure) {
      if (failure instanceof ApiError && failure.fields.length > 0) {
        setFieldErrors(
          Object.fromEntries(failure.fields.map((item) => [item.field.replace(/^claim\./, ""), item.message])),
        );
      } else {
        setError(errorMessage(failure));
      }
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="max-w-2xl space-y-6">
      {error ? <Notice tone="critical" title={error} /> : null}

      <Panel className="space-y-4 p-6">
        <h2 className="font-medium">Coverage</h2>
        <Field label="Policy" htmlFor="policyId" error={fieldErrors.policyId}>
          <Select id="policyId" value={values.policyId} onChange={(event) => set("policyId", event.target.value)}>
            <option value="">Choose a policy</option>
            {policies.map((policy) => (
              <option key={policy.id} value={policy.id}>
                {policy.policyNumber}: {policy.productName}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Claim type" htmlFor="type" error={fieldErrors.type}>
          <Select id="type" value={values.type} onChange={(event) => set("type", event.target.value as ClaimType)}>
            {Object.entries(CLAIM_TYPE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
        </Field>
      </Panel>

      <Panel className="space-y-4 p-6">
        <h2 className="font-medium">People and treatment</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Claimant" htmlFor="claimantName" error={fieldErrors.claimantName}>
            <Input id="claimantName" value={values.claimantName} onChange={(event) => set("claimantName", event.target.value)} />
          </Field>
          <Field label="Patient" htmlFor="patientName" error={fieldErrors.patientName}>
            <Input id="patientName" value={values.patientName} onChange={(event) => set("patientName", event.target.value)} />
          </Field>
        </div>
        <Field label="Hospital" htmlFor="hospitalName" error={fieldErrors.hospitalName}>
          <Input id="hospitalName" value={values.hospitalName} onChange={(event) => set("hospitalName", event.target.value)} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Admission date" htmlFor="admissionDate" error={fieldErrors.admissionDate}>
            <Input id="admissionDate" type="date" value={values.admissionDate} onChange={(event) => set("admissionDate", event.target.value)} />
          </Field>
          <Field label="Discharge date" htmlFor="dischargeDate" error={fieldErrors.dischargeDate}>
            <Input id="dischargeDate" type="date" value={values.dischargeDate} onChange={(event) => set("dischargeDate", event.target.value)} />
          </Field>
        </div>
        <Field label="Claimed amount (₹)" htmlFor="claimedAmount" error={fieldErrors.claimedAmount}>
          <Input
            id="claimedAmount"
            inputMode="decimal"
            placeholder="0.00"
            value={values.claimedAmount}
            onChange={(event) => set("claimedAmount", event.target.value)}
          />
        </Field>
      </Panel>

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={() => router.back()}>
          Cancel
        </Button>
        <Button type="submit" loading={busy}>
          {claim ? "Save changes" : "Create claim"}
        </Button>
      </div>
    </form>
  );
}
