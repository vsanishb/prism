"use client";

import { Activity, FileStack, LayoutGrid, LogOut, ScrollText, ShieldCheck, Users, type LucideIcon } from "lucide-react";
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
}

const NAVIGATION: NavItem[] = [
  { href: "/overview", label: "Overview", icon: LayoutGrid },
  { href: "/claims", label: "Claims", icon: FileStack, permission: "CLAIM_READ" },
  { href: "/policies", label: "Policies", icon: ShieldCheck, permission: "POLICY_READ" },
  { href: "/admin/users", label: "Users", icon: Users, permission: "USERS_MANAGE" },
  { href: "/audit", label: "Audit trail", icon: ScrollText, permission: "AUDIT_READ" },
  { href: "/system", label: "System status", icon: Activity, permission: "SYSTEM_VIEW" },
];

export function AppShell({
  user,
  syntheticEnvironment,
  children,
}: {
  user: CurrentUser;
  syntheticEnvironment: boolean;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);
  const items = NAVIGATION.filter((item) => !item.permission || user.permissions.includes(item.permission));

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
      <aside className="flex shrink-0 flex-col border-b border-line bg-surface md:sticky md:top-0 md:h-screen md:w-60 md:border-r md:border-b-0">
        <div className="flex h-14 items-center px-5">
          <Logo />
        </div>
        <nav aria-label="Main" className="flex gap-1 overflow-x-auto px-3 pb-2 md:flex-1 md:flex-col md:pt-3">
          {items.map(({ href, label, icon: Icon }) => {
            const active = pathname === href || pathname.startsWith(`${href}/`);
            return (
              <Link
                key={href}
                href={href}
                aria-current={active ? "page" : undefined}
                className={`flex h-9 items-center gap-2.5 rounded-control px-2.5 text-[13px] font-medium whitespace-nowrap transition-colors ${
                  active ? "bg-accent-soft text-accent" : "text-ink-muted hover:bg-subtle hover:text-ink"
                }`}
              >
                <Icon aria-hidden className="size-4" />
                {label}
              </Link>
            );
          })}
        </nav>
        <div className="hidden border-t border-line p-3 md:block">
          {syntheticEnvironment ? (
            <p className="mb-3 rounded-control bg-caution-soft px-2.5 py-1.5 text-xs text-caution">Synthetic data only</p>
          ) : null}
          <div className="flex items-center gap-2.5">
            <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-subtle text-xs font-medium text-ink-muted">
              {initials(user.fullName)}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium">{user.fullName}</p>
              <p className="truncate text-xs text-ink-muted">{user.roles.map((role) => ROLE_LABELS[role]).join(", ")}</p>
            </div>
            <button
              type="button"
              aria-label="Sign out"
              disabled={signingOut}
              onClick={signOut}
              className="rounded-control p-1.5 text-ink-muted transition-colors hover:bg-subtle hover:text-ink"
            >
              <LogOut aria-hidden className="size-4" />
            </button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-5xl px-4 py-8 md:px-10 md:py-10">{children}</div>
        <div className="border-t border-line px-4 py-3 md:hidden">
          <button type="button" onClick={signOut} className="text-[13px] font-medium text-ink-muted">
            Sign out
          </button>
        </div>
      </main>
    </div>
  );
}
