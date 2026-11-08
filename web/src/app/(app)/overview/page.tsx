import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { LoadFailed, PageHeader, Panel, SectionTitle } from "@/components/ui/layout";
import { Dashboard } from "@/features/overview/dashboard";
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "@/lib/labels";
import { requireCurrentUser, serverFetch } from "@/lib/server-api";
import type { Overview, Permission } from "@/lib/types";

export const metadata: Metadata = { title: "Overview" };

const DESTINATIONS: { permission: Permission; href: string; title: string; description: string }[] = [
  {
    permission: "CLAIM_READ",
    href: "/claims",
    title: "Work with claims",
    description: "Find claims, open their documents, and follow their history.",
  },
  {
    permission: "POLICY_READ",
    href: "/policies",
    title: "Browse policies",
    description: "See registered policies, versions, and wordings.",
  },
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

const today = new Intl.DateTimeFormat("en-IN", { dateStyle: "full", timeZone: "Asia/Kolkata" });

export default async function OverviewPage() {
  const user = await requireCurrentUser();
  if (!user) {
    return <LoadFailed />;
  }
  const destinations = DESTINATIONS.filter((item) => user.permissions.includes(item.permission));
  const canReview = ["REVIEW_QUEUE", "SENIOR_REVIEW_QUEUE", "INVESTIGATION_QUEUE"].some((permission) =>
    user.permissions.includes(permission as Permission),
  );
  const overview = user.permissions.includes("CLAIM_READ") ? await serverFetch<Overview>("/api/v1/overview") : null;

  return (
    <>
      <PageHeader title={`Welcome, ${user.fullName}`} description={today.format(new Date())} />

      {overview ? (
        <section className="mb-12">{overview.ok ? <Dashboard overview={overview.data} canReview={canReview} /> : <LoadFailed />}</section>
      ) : null}

      {destinations.length > 0 ? (
        <section className="mb-12">
          <SectionTitle>Go to</SectionTitle>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {destinations.map((item) => (
              <Link key={item.href} href={item.href} className="group">
                <Panel className="h-full p-5 transition-colors group-hover:border-line-strong">
                  <p className="flex items-center justify-between font-medium">
                    {item.title}
                    <ArrowRight aria-hidden className="size-4 text-ink-faint transition-colors group-hover:text-accent" />
                  </p>
                  <p className="mt-1 text-sm text-ink-muted">{item.description}</p>
                </Panel>
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <SectionTitle>Your roles</SectionTitle>
      <Panel className="divide-y divide-line">
        {user.roles.map((role) => (
          <div key={role} className="px-5 py-4">
            <p className="font-medium">{ROLE_LABELS[role]}</p>
            <p className="mt-0.5 text-sm text-ink-muted">{ROLE_DESCRIPTIONS[role]}</p>
          </div>
        ))}
      </Panel>
    </>
  );
}
