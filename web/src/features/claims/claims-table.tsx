"use client";

import { FileStack, Search } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { EmptyState } from "@/components/ui/feedback";
import { Input, Select } from "@/components/ui/field";
import { Panel } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { formatDate, formatMoney } from "@/lib/format";
import { CLAIM_STATUS_LABELS } from "@/lib/labels";
import { CLAIM_STATUSES, type ClaimSummary, type Page } from "@/lib/types";
import { ClaimStatusBadge } from "./claim-status";

export function ClaimsTable({
  page,
  status,
  search,
  paged,
}: {
  page: Page<ClaimSummary>;
  status: string;
  search: string;
  paged: boolean;
}) {
  const router = useRouter();
  const [term, setTerm] = useState(search);

  function href(overrides: { status?: string; search?: string; cursor?: string }) {
    const next = { status, search, ...overrides };
    const params = new URLSearchParams();
    if (next.status) params.set("status", next.status);
    if (next.search) params.set("search", next.search);
    if (next.cursor) params.set("cursor", next.cursor);
    return `/claims?${params}`;
  }

  function applySearch(event: FormEvent) {
    event.preventDefault();
    router.push(href({ search: term }));
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap gap-3">
        <form onSubmit={applySearch} className="relative w-full max-w-xs">
          <Search aria-hidden className="pointer-events-none absolute top-2.5 left-3 size-4 text-ink-faint" />
          <Input
            aria-label="Search claims"
            placeholder="Search by number or name"
            className="pl-9"
            value={term}
            onChange={(event) => setTerm(event.target.value)}
          />
        </form>
        <div className="w-52">
          <Select aria-label="Filter by status" value={status} onChange={(event) => router.push(href({ status: event.target.value }))}>
            <option value="">All statuses</option>
            {CLAIM_STATUSES.map((value) => (
              <option key={value} value={value}>
                {CLAIM_STATUS_LABELS[value]}
              </option>
            ))}
          </Select>
        </div>
      </div>

      <Panel>
        {page.items.length === 0 ? (
          <EmptyState icon={FileStack} title={status || search ? "No claims match these filters" : "No claims yet"} />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Claim</Th>
                <Th>Patient</Th>
                <Th>Admission</Th>
                <Th className="text-right">Claimed</Th>
                <Th>Status</Th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((claim) => (
                <tr key={claim.id} className="transition-colors hover:bg-canvas">
                  <Td className="whitespace-nowrap">
                    <Link href={`/claims/${claim.id}`} className="font-medium hover:text-accent">
                      {claim.claimNumber}
                    </Link>
                  </Td>
                  <Td>
                    <p>{claim.patientName}</p>
                    <p className="text-xs text-ink-muted">{claim.hospitalName}</p>
                  </Td>
                  <Td className="whitespace-nowrap text-ink-muted">{formatDate(claim.admissionDate)}</Td>
                  <Td className="text-right whitespace-nowrap tabular-nums">{formatMoney(claim.claimedAmount, claim.currency)}</Td>
                  <Td>
                    <ClaimStatusBadge status={claim.status} />
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>

      <div className="mt-4 flex gap-4 text-[13px] font-medium">
        {paged ? (
          <Link href={href({ cursor: "" })} className="text-accent">
            Back to latest
          </Link>
        ) : null}
        {page.nextCursor ? (
          <Link href={href({ cursor: page.nextCursor })} className="text-accent">
            Older claims
          </Link>
        ) : null}
      </div>
    </>
  );
}
