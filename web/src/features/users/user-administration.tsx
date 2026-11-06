"use client";

import { Ellipsis, Plus, Search, Users } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { DropdownMenu } from "radix-ui";
import { useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Badge, EmptyState, Notice } from "@/components/ui/feedback";
import { Input } from "@/components/ui/field";
import { PageHeader, Panel, SectionTitle } from "@/components/ui/layout";
import { Table, Td, Th } from "@/components/ui/table";
import { apiRequest, errorMessage } from "@/lib/client-api";
import { formatDateTime } from "@/lib/format";
import { ROLE_LABELS } from "@/lib/labels";
import type { IssuedLink, Page, PendingInvitation, User } from "@/lib/types";
import { EditRolesDialog } from "./edit-roles-dialog";
import { InviteDialog } from "./invite-dialog";
import { OneTimeLinkDialog } from "./one-time-link";

function MenuItem({ onSelect, danger, children }: { onSelect: () => void; danger?: boolean; children: ReactNode }) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className={`cursor-pointer rounded-[6px] px-2.5 py-1.5 text-[13px] outline-none data-[highlighted]:bg-subtle ${danger ? "text-critical" : ""}`}
    >
      {children}
    </DropdownMenu.Item>
  );
}

export function UserAdministration({
  page,
  invitations,
  currentUserId,
  search,
  paged,
}: {
  page: Page<User>;
  invitations: PendingInvitation[];
  currentUserId: string;
  search: string;
  paged: boolean;
}) {
  const router = useRouter();
  const [inviting, setInviting] = useState(false);
  const [editing, setEditing] = useState<User | null>(null);
  const [issued, setIssued] = useState<{ title: string; link: IssuedLink } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [term, setTerm] = useState(search);

  async function act(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    }
  }

  function applySearch(event: FormEvent) {
    event.preventDefault();
    router.push(term ? `/admin/users?search=${encodeURIComponent(term)}` : "/admin/users");
  }

  return (
    <>
      <PageHeader
        title="Users"
        description="Staff accounts and their roles. Roles decide what each person can do, and the server enforces them."
        actions={
          <Button onClick={() => setInviting(true)}>
            <Plus aria-hidden className="size-4" />
            Invite
          </Button>
        }
      />

      {error ? (
        <div className="mb-4">
          <Notice tone="critical" title={error} />
        </div>
      ) : null}

      <form onSubmit={applySearch} className="relative mb-4 max-w-xs">
        <Search aria-hidden className="pointer-events-none absolute top-2.5 left-3 size-4 text-ink-faint" />
        <Input aria-label="Search users" placeholder="Search by name or email" className="pl-9" value={term} onChange={(event) => setTerm(event.target.value)} />
      </form>

      <Panel>
        {page.items.length === 0 ? (
          <EmptyState icon={Users} title="No users found">
            Try a different search.
          </EmptyState>
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Name</Th>
                <Th>Roles</Th>
                <Th>Status</Th>
                <Th>Last sign-in</Th>
                <Th>
                  <span className="sr-only">Actions</span>
                </Th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((user) => (
                <tr key={user.id}>
                  <Td>
                    <p className="font-medium">
                      {user.fullName}
                      {user.id === currentUserId ? <span className="ml-1.5 font-normal text-ink-faint">(you)</span> : null}
                    </p>
                    <p className="text-xs text-ink-muted">{user.email}</p>
                  </Td>
                  <Td>
                    <div className="flex flex-wrap gap-1">
                      {user.roles.map((role) => (
                        <Badge key={role}>{ROLE_LABELS[role]}</Badge>
                      ))}
                    </div>
                  </Td>
                  <Td>
                    <Badge tone={user.status === "ACTIVE" ? "positive" : "neutral"}>{user.status === "ACTIVE" ? "Active" : "Deactivated"}</Badge>
                  </Td>
                  <Td className="whitespace-nowrap text-ink-muted">{formatDateTime(user.lastSignInAt)}</Td>
                  <Td className="text-right">
                    <DropdownMenu.Root>
                      <DropdownMenu.Trigger aria-label={`Actions for ${user.fullName}`} className="rounded-control p-1.5 text-ink-muted hover:bg-subtle">
                        <Ellipsis aria-hidden className="size-4" />
                      </DropdownMenu.Trigger>
                      <DropdownMenu.Portal>
                        <DropdownMenu.Content align="end" sideOffset={4} className="z-30 min-w-44 rounded-control border border-line bg-surface p-1 shadow-overlay">
                          <MenuItem onSelect={() => setEditing(user)}>Change roles</MenuItem>
                          {user.status === "ACTIVE" ? (
                            <>
                              <MenuItem
                                onSelect={() =>
                                  act(async () => {
                                    const link = await apiRequest<IssuedLink>("POST", `/api/v1/users/${user.id}/password-reset`);
                                    setIssued({ title: `Password reset for ${user.fullName}`, link });
                                  })
                                }
                              >
                                Reset password
                              </MenuItem>
                              <MenuItem danger onSelect={() => act(() => apiRequest("POST", `/api/v1/users/${user.id}/deactivation`))}>
                                Deactivate
                              </MenuItem>
                            </>
                          ) : (
                            <MenuItem onSelect={() => act(() => apiRequest("POST", `/api/v1/users/${user.id}/reactivation`))}>Reactivate</MenuItem>
                          )}
                        </DropdownMenu.Content>
                      </DropdownMenu.Portal>
                    </DropdownMenu.Root>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>

      <div className="mt-4 flex gap-4 text-[13px] font-medium">
        {paged ? (
          <Link href="/admin/users" className="text-accent">
            Back to first page
          </Link>
        ) : null}
        {page.nextCursor ? (
          <Link href={`/admin/users?cursor=${page.nextCursor}${search ? `&search=${encodeURIComponent(search)}` : ""}`} className="text-accent">
            Next page
          </Link>
        ) : null}
      </div>

      {invitations.length > 0 ? (
        <div className="mt-10">
          <SectionTitle>Pending invitations</SectionTitle>
          <Panel className="divide-y divide-line">
            {invitations.map((invitation) => (
              <div key={invitation.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
                <div>
                  <p className="font-medium">{invitation.fullName}</p>
                  <p className="text-xs text-ink-muted">
                    {invitation.email} · Expires {formatDateTime(invitation.expiresAt)}
                  </p>
                </div>
                <Button variant="ghost" onClick={() => act(() => apiRequest("DELETE", `/api/v1/invitations/${invitation.id}`))}>
                  Revoke
                </Button>
              </div>
            ))}
          </Panel>
        </div>
      ) : null}

      <InviteDialog
        open={inviting}
        onOpenChange={setInviting}
        onInvited={(link) => {
          setInviting(false);
          setIssued({ title: "Invitation link", link });
          router.refresh();
        }}
      />
      {editing ? (
        <EditRolesDialog
          user={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            router.refresh();
          }}
        />
      ) : null}
      <OneTimeLinkDialog title={issued?.title ?? ""} issued={issued?.link ?? null} onClose={() => setIssued(null)} />
    </>
  );
}
