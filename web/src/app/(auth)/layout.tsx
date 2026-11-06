import type { ReactNode } from "react";
import { Logo } from "@/components/brand";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center px-4 py-12">
      <Logo className="mb-8" />
      <div className="w-full max-w-sm rounded-panel border border-line bg-surface p-7">{children}</div>
      <p className="mt-6 text-xs text-ink-faint">Claims intelligence for authorized staff</p>
    </main>
  );
}
