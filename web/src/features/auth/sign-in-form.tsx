"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { apiRequest, errorMessage } from "@/lib/client-api";
import type { CurrentUser } from "@/lib/types";

export function SignInForm() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiRequest<CurrentUser>("POST", "/api/v1/auth/sign-in", { email, password });
      router.replace("/overview");
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
      setPassword("");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="mb-6">
        <h1 className="text-lg font-semibold tracking-tight">Sign in</h1>
        <p className="mt-0.5 text-[13px] text-ink-muted">Use your staff account.</p>
      </div>
      {error ? <Notice tone="critical" title={error} /> : null}
      <Field label="Email" htmlFor="email">
        <Input
          id="email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </Field>
      <Field label="Password" htmlFor="password">
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </Field>
      <Button type="submit" loading={busy} className="w-full">
        Sign in
      </Button>
      <p className="text-center text-xs text-ink-muted">Forgotten your password? Ask an administrator for a reset link.</p>
    </form>
  );
}
