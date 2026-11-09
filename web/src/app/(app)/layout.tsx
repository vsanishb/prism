import type { ReactNode } from "react";
import { LoadFailed } from "@/components/ui/layout";
import { AppShell } from "@/features/shell/app-shell";
import { requireCurrentUser } from "@/lib/server-api";

export default async function ApplicationLayout({ children }: { children: ReactNode }) {
  const user = await requireCurrentUser();
  if (!user) {
    return (
      <main className="mx-auto max-w-xl px-4 py-24">
        <LoadFailed />
      </main>
    );
  }
  return (
    <AppShell user={user} localEnvironment={process.env.PRISM_ENVIRONMENT === "local"}>
      {children}
    </AppShell>
  );
}
