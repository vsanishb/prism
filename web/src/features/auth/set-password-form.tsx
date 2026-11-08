"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Notice, Skeleton } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { apiRequest, errorMessage } from "@/lib/client-api";
import type { TokenDetails } from "@/lib/types";

type Stage =
  | { name: "loading" }
  | { name: "invalid"; message: string }
  | { name: "ready"; token: string; details: TokenDetails }
  | { name: "done"; purpose: TokenDetails["purpose"] };

const INVALID_LINK = "This link is invalid or has expired. Ask an administrator for a new one.";

/**
 * The token lives in the URL fragment, which is never sent to servers. It is removed from the
 * address bar at once, so that it does not linger in history or screen shares.
 */
async function readLink(expectedPurpose: TokenDetails["purpose"]): Promise<Stage> {
  const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
  window.history.replaceState(null, "", window.location.pathname);
  if (!token) {
    return { name: "invalid", message: INVALID_LINK };
  }
  try {
    const details = await apiRequest<TokenDetails>("POST", "/api/v1/auth/tokens/inspect", { token });
    return details.purpose === expectedPurpose
      ? { name: "ready", token, details }
      : { name: "invalid", message: INVALID_LINK };
  } catch {
    return { name: "invalid", message: INVALID_LINK };
  }
}

export function SetPasswordForm({ expectedPurpose }: { expectedPurpose: TokenDetails["purpose"] }) {
  const linkRead = useRef(false);
  const [stage, setStage] = useState<Stage>({ name: "loading" });
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // Effects can run twice in development, and the first run removes the token from the URL.
    if (linkRead.current) {
      return;
    }
    linkRead.current = true;
    readLink(expectedPurpose).then((result) => {
      if (result.name === "ready") {
        setFullName(result.details.fullName ?? "");
      }
      setStage(result);
    });
  }, [expectedPurpose]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (stage.name !== "ready") {
      return;
    }
    if (password !== confirmation) {
      setError("The passwords do not match.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiRequest("POST", "/api/v1/auth/tokens/redeem", {
        token: stage.token,
        password,
        fullName: stage.details.purpose === "INVITATION" ? fullName : undefined,
      });
      setStage({ name: "done", purpose: stage.details.purpose });
    } catch (failure) {
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  if (stage.name === "loading") {
    return (
      <div className="space-y-3" aria-label="Checking your link">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-9" />
        <Skeleton className="h-9" />
      </div>
    );
  }

  if (stage.name === "invalid") {
    return (
      <div className="space-y-4">
        <Notice tone="critical" title="This link cannot be used">
          {stage.message}
        </Notice>
        <Link href="/sign-in" className="block text-center text-sm font-medium text-accent">
          Go to sign in
        </Link>
      </div>
    );
  }

  if (stage.name === "done") {
    return (
      <div className="space-y-4">
        <Notice tone="positive" title={stage.purpose === "INVITATION" ? "Your account is ready" : "Your password has been changed"}>
          You can now sign in.
        </Notice>
        <Link href="/sign-in" className="block text-center text-sm font-medium text-accent">
          Go to sign in
        </Link>
      </div>
    );
  }

  const invitation = stage.details.purpose === "INVITATION";
  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="mb-8">
        <h1 className="text-[1.75rem] leading-tight font-semibold tracking-tight">{invitation ? "Set up your account" : "Choose a new password"}</h1>
        <p className="mt-1.5 text-ink-muted">{stage.details.email}</p>
      </div>
      {error ? <Notice tone="critical" title={error} /> : null}
      {invitation ? (
        <Field label="Full name" htmlFor="fullName">
          <Input id="fullName" required value={fullName} onChange={(event) => setFullName(event.target.value)} />
        </Field>
      ) : null}
      <Field label="Password" htmlFor="password" hint="At least 12 characters. A short phrase works well.">
        <Input
          id="password"
          type="password"
          autoComplete="new-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </Field>
      <Field label="Confirm password" htmlFor="confirmation">
        <Input
          id="confirmation"
          type="password"
          autoComplete="new-password"
          required
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
        />
      </Field>
      <Button type="submit" loading={busy} className="w-full">
        {invitation ? "Create account" : "Change password"}
      </Button>
    </form>
  );
}
