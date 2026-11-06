import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { LoadFailed, PageHeader, Panel, SectionTitle } from "@/components/ui/layout";
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "@/lib/labels";
import { requireCurrentUser } from "@/lib/server-api";
import type { Permission } from "@/lib/types";

export const metadata: Metadata = { title: "Overview" };

const DESTINATIONS: { permission: Permission; href: string; title: string; description: string }[] = [
  {
    permission: "USERS_MANAGE",
    href: "/admin/users",
    title: "Manage users",
    description: "Invite staff, assign roles, and reset access.",
  },
  {
    permission: "AUDIT_READ",
    href: "/audit",
    title: "Review the audit trail",
    description: "Search recorded actions and verify their integrity.",
  },
  {
    permission: "SYSTEM_VIEW",
    href: "/system",
    title: "Check system status",
    description: "Run live checks across Prism's services.",
  },
];

export default async function OverviewPage() {
  const user = await requireCurrentUser();
  if (!user) {
    return <LoadFailed />;
  }
  const destinations = DESTINATIONS.filter((item) => user.permissions.includes(item.permission));

  return (
    <>
      <PageHeader title={`Welcome, ${user.fullName}`} description="Your access in Prism." />

      {destinations.length > 0 ? (
        <div className="mb-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {destinations.map((item) => (
            <Link key={item.href} href={item.href} className="group">
              <Panel className="h-full p-5 transition-colors group-hover:border-line-strong">
                <p className="flex items-center justify-between font-medium">
                  {item.title}
                  <ArrowRight aria-hidden className="size-4 text-ink-faint transition-colors group-hover:text-accent" />
                </p>
                <p className="mt-1 text-[13px] text-ink-muted">{item.description}</p>
              </Panel>
            </Link>
          ))}
        </div>
      ) : null}

      <SectionTitle>Your roles</SectionTitle>
      <Panel className="divide-y divide-line">
        {user.roles.map((role) => (
          <div key={role} className="px-5 py-4">
            <p className="font-medium">{ROLE_LABELS[role]}</p>
            <p className="mt-0.5 text-[13px] text-ink-muted">{ROLE_DESCRIPTIONS[role]}</p>
          </div>
        ))}
      </Panel>
    </>
  );
}
