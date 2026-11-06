"use client";

import { Plus, Search, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState, Notice } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { PageHeader, Panel } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { ApiError, apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime } from "@/lib/format";
import type { Page, PolicySummary } from "@/lib/types";

function CreatePolicyDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const router = useRouter();
  const [policyNumber, setPolicyNumber] = useState("");
  const [productName, setProductName] = useState("");
  const [policyholderName, setPolicyholderName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const created = await apiRequest<PolicySummary>("POST", "/api/v1/policies", { policyNumber, productName, policyholderName });
      router.push(`/policies/${created.id}`);
    } catch (failure) {
      if (failure instanceof ApiError && failure.fields.length > 0) {
        setFields(Object.fromEntries(failure.fields.map((item) => [item.field, item.message])));
      } else {
        setError(errorMessage(failure));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Register a policy" description="You can add its versions and wordings next.">
      <form onSubmit={submit} className="space-y-4">
        {error ? <Notice tone="critical" title={error} /> : null}
        <Field label="Policy number" htmlFor="policyNumber" error={fields.policyNumber}>
          <Input id="policyNumber" value={policyNumber} onChange={(event) => setPolicyNumber(event.target.value)} />
        </Field>
        <Field label="Product" htmlFor="productName" error={fields.productName}>
          <Input id="productName" value={productName} onChange={(event) => setProductName(event.target.value)} />
        </Field>
        <Field label="Policyholder" htmlFor="policyholderName" error={fields.policyholderName}>
          <Input id="policyholderName" value={policyholderName} onChange={(event) => setPolicyholderName(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Register policy
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

export function PolicyRegister({
  page,
  search,
  paged,
  canManage,
}: {
  page: Page<PolicySummary>;
  search: string;
  paged: boolean;
  canManage: boolean;
}) {
  const router = useRouter();
  const [term, setTerm] = useState(search);
  const [creating, setCreating] = useState(false);

  function applySearch(event: FormEvent) {
    event.preventDefault();
    router.push(term ? `/policies?search=${encodeURIComponent(term)}` : "/policies");
  }

  return (
    <>
      <PageHeader
        title="Policies"
        description="Registered policies, their versions, and their wordings."
        actions={
          canManage ? (
            <Button onClick={() => setCreating(true)}>
              <Plus aria-hidden className="size-4" />
              Register policy
            </Button>
          ) : undefined
        }
      />
      <form onSubmit={applySearch} className="relative mb-4 max-w-xs">
        <Search aria-hidden className="pointer-events-none absolute top-2.5 left-3 size-4 text-ink-faint" />
        <Input aria-label="Search policies" placeholder="Search policies" className="pl-9" value={term} onChange={(event) => setTerm(event.target.value)} />
      </form>
      <Panel>
        {page.items.length === 0 ? (
          <EmptyState icon={ShieldCheck} title={search ? "No policies match this search" : "No policies registered yet"} />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Policy</Th>
                <Th>Product</Th>
                <Th>Policyholder</Th>
                <Th>Registered</Th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((policy) => (
                <tr key={policy.id} className="transition-colors hover:bg-canvas">
                  <Td className="whitespace-nowrap">
                    <Link href={`/policies/${policy.id}`} className="font-medium hover:text-accent">
                      {policy.policyNumber}
                    </Link>
                  </Td>
                  <Td>{policy.productName}</Td>
                  <Td className="text-ink-muted">{policy.policyholderName}</Td>
                  <Td className="whitespace-nowrap text-ink-muted">{formatDateTime(policy.createdAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      <div className="mt-4 flex gap-4 text-[13px] font-medium">
        {paged ? (
          <Link href="/policies" className="text-accent">
            Back to first page
          </Link>
        ) : null}
        {page.nextCursor ? (
          <Link href={`/policies?cursor=${page.nextCursor}${search ? `&search=${encodeURIComponent(search)}` : ""}`} className="text-accent">
            Next page
          </Link>
        ) : null}
      </div>
      <CreatePolicyDialog open={creating} onOpenChange={setCreating} />
    </>
  );
}
