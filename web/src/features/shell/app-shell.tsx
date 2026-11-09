"use client";

import { Activity, ClipboardCheck, FileStack, FlaskConical, LayoutGrid, LogOut, ScrollText, ShieldCheck, Users, type LucideIcon } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { Logo } from "@/components/brand";
import { apiRequest } from "@/lib/client-api";
import { initials } from "@/lib/format";
import { ROLE_LABELS } from "@/lib/labels";
import type { CurrentUser, Permission } from "@/lib/types";

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  permission?: Permission;
  /** Shown to anyone with any of these. */
  anyOf?: Permission[];
  /** Shown only in the local environment. Core refuses the page anywhere else. */
  localOnly?: boolean;
}

const SECTIONS: { title: string; items: NavItem[] }[] = [
  {
    title: "Work",
    items: [
      { href: "/overview", label: "Overview", icon: LayoutGrid },
      { href: "/review", label: "Review", icon: ClipboardCheck, anyOf: ["REVIEW_QUEUE", "SENIOR_REVIEW_QUEUE", "INVESTIGATION_QUEUE"] },
      { href: "/claims", label: "Claims", icon: FileStack, permission: "CLAIM_READ" },
      { href: "/policies", label: "Policies", icon: ShieldCheck, permission: "POLICY_READ" },
      { href: "/demonstrations", label: "Risk demonstrations", icon: FlaskConical, permission: "CLAIM_MANAGE", localOnly: true },
    ],
  },
  {
    title: "Administration",
    items: [
      { href: "/admin/users", label: "Users", icon: Users, permission: "USERS_MANAGE" },
      { href: "/audit", label: "Audit trail", icon: ScrollText, permission: "AUDIT_READ" },
      { href: "/system", label: "System status", icon: Activity, permission: "SYSTEM_VIEW" },
    ],
  },
];

function NavLink({ item, active }: { item: NavItem; active: boolean }) {
  const Icon = item.icon;
  return (
    <Link
      href={item.href}
      aria-current={active ? "page" : undefined}
      className={`group relative flex h-11 shrink-0 items-center gap-3 rounded-control px-3 text-[0.9375rem] font-medium whitespace-nowrap transition-colors ${
        active ? "bg-accent-soft text-accent" : "text-ink-muted hover:bg-subtle hover:text-ink"
      }`}
    >
      {active ? <span aria-hidden className="absolute top-2.5 bottom-2.5 left-0 hidden w-[3px] rounded-r bg-accent md:block" /> : null}
      <Icon aria-hidden className={`size-[1.2rem] shrink-0 ${active ? "text-accent" : "text-ink-faint group-hover:text-ink-muted"}`} />
      {item.label}
    </Link>
  );
}

export function AppShell({
  user,
  localEnvironment,
  children,
}: {
  user: CurrentUser;
  localEnvironment: boolean;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);
  const visible = (item: NavItem) =>
    (!item.permission || user.permissions.includes(item.permission)) &&
    (!item.anyOf || item.anyOf.some((permission) => user.permissions.includes(permission))) &&
    (!item.localOnly || localEnvironment);
  const sections = SECTIONS.map((section) => ({ ...section, items: section.items.filter(visible) })).filter(
    (section) => section.items.length > 0,
  );

  async function signOut() {
    setSigningOut(true);
    try {
      await apiRequest("POST", "/api/v1/auth/sign-out");
    } finally {
      router.replace("/sign-in");
      router.refresh();
    }
  }

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-line bg-surface md:sticky md:top-0 md:h-screen md:w-72 md:border-r md:border-b-0">
        <div className="flex h-16 items-center px-6 md:h-[4.5rem]">
          <Logo size="lg" />
        </div>
        <nav aria-label="Main" className="flex gap-1 overflow-x-auto px-3 pb-3 md:flex-1 md:flex-col md:gap-6 md:overflow-y-auto md:px-4 md:pt-2">
          {sections.map((section) => (
            <div key={section.title} className="flex gap-1 md:flex-col">
              <p className="hidden px-3 pb-1.5 text-xs font-medium tracking-wide text-ink-faint uppercase md:block">{section.title}</p>
              {section.items.map((item) => (
                <NavLink key={item.href} item={item} active={pathname === item.href || pathname.startsWith(`${item.href}/`)} />
              ))}
            </div>
          ))}
        </nav>
        <div className="hidden border-t border-line p-4 md:block">
          <div className="flex items-center gap-3 rounded-control px-1 py-1">
            <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent-soft text-sm font-semibold text-accent">
              {initials(user.fullName)}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{user.fullName}</p>
              <p className="truncate text-xs text-ink-muted">{user.roles.map((role) => ROLE_LABELS[role]).join(", ")}</p>
            </div>
            <button
              type="button"
              aria-label="Sign out"
              title="Sign out"
              disabled={signingOut}
              onClick={signOut}
              className="rounded-control p-2 text-ink-muted transition-colors hover:bg-subtle hover:text-ink"
            >
              <LogOut aria-hidden className="size-[1.1rem]" />
            </button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-6xl px-4 py-8 md:px-12 md:py-11">{children}</div>
        <div className="border-t border-line px-4 py-3 md:hidden">
          <button type="button" onClick={signOut} className="text-sm font-medium text-ink-muted">
            Sign out
          </button>
        </div>
      </main>
    </div>
  );
}
