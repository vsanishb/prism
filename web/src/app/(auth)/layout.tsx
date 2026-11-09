import type { ReactNode } from "react";
import { Logo } from "@/components/brand";

const PRINCIPLES = [
  { title: "Every finding has a source", body: "Facts, policy clauses, and risk indicators open the page they came from." },
  { title: "Arithmetic is never guessed", body: "Amounts, dates, and limits are computed by code, not by a model." },
  { title: "People decide", body: "Prism prepares the claim. A reviewer approves, reduces, or denies it." },
];

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="grid min-h-screen lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      <section className="hidden flex-col justify-between border-r border-line bg-subtle px-14 py-12 lg:flex">
        <Logo size="lg" />
        <div className="max-w-md">
          <h2 className="text-[2rem] leading-tight font-semibold tracking-tight">Health claims, read and checked before anyone decides.</h2>
          <p className="mt-4 text-ink-muted">
            Prism reads claim documents, checks them against the policy wording, and screens them for risk, so that
            reviewers start from the evidence.
          </p>
          <ul className="mt-10 space-y-6">
            {PRINCIPLES.map((principle, index) => (
              <li key={principle.title} className="flex gap-4">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full border border-line-strong bg-surface text-xs font-semibold text-ink-muted tabular-nums">
                  {index + 1}
                </span>
                <div>
                  <p className="font-medium">{principle.title}</p>
                  <p className="mt-0.5 text-sm text-ink-muted">{principle.body}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
        <p className="text-xs text-ink-faint">Claims intelligence for authorized staff</p>
      </section>

      <section className="flex flex-col items-center justify-center px-4 py-12 sm:px-8">
        <Logo size="lg" className="mb-10 lg:hidden" />
        <div className="w-full max-w-[25rem]">{children}</div>
      </section>
    </main>
  );
}
