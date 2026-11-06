import type { Metadata } from "next";
import { AccessDenied, LoadFailed, PageHeader } from "@/components/ui/layout";
import { UserAdministration } from "@/features/users/user-administration";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { Page, PendingInvitation, User } from "@/lib/types";

export const metadata: Metadata = { title: "Users" };

export default async function UsersPage({ searchParams }: { searchParams: Promise<{ search?: string; cursor?: string }> }) {
  const { search, cursor } = await searchParams;
  const query = new URLSearchParams({ limit: "25" });
  if (search) query.set("search", search);
  if (cursor) query.set("cursor", cursor);

  const [users, invitations, current] = await Promise.all([
    serverFetch<Page<User>>(`/api/v1/users?${query}`),
    serverFetch<PendingInvitation[]>("/api/v1/invitations"),
    requireCurrentUser(),
  ]);

  if (!users.ok && users.status === 403) {
    return (
      <>
        <PageHeader title="Users" />
        <AccessDenied />
      </>
    );
  }
  if (!users.ok || !invitations.ok || !current) {
    return (
      <>
        <PageHeader title="Users" />
        <LoadFailed />
      </>
    );
  }
  return <UserAdministration page={users.data} invitations={invitations.data} currentUserId={current.id} search={search ?? ""} paged={Boolean(cursor)} />;
}
