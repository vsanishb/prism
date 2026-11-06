"use client";

import { ShieldCheck } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/feedback";
import { apiRequest, errorMessage } from "@/lib/client-api";
import type { ChainVerification } from "@/lib/types";

export function VerifyChainButton() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ChainVerification | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function verify() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await apiRequest<ChainVerification>("POST", "/api/v1/audit/verification"));
      router.refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mb-6 space-y-3">
      <Button variant="secondary" loading={busy} onClick={verify}>
        {busy ? null : <ShieldCheck aria-hidden className="size-4" />}
        Verify integrity
      </Button>
      {error ? <Notice tone="critical" title={error} /> : null}
      {result?.valid ? (
        <Notice tone="positive" title="The audit trail is intact">
          Checked {result.eventsChecked.toLocaleString("en-IN")} events across {result.streamsChecked}{" "}
          {result.streamsChecked === 1 ? "stream" : "streams"}.
        </Notice>
      ) : null}
      {result && !result.valid ? (
        <Notice tone="critical" title="The audit trail has been altered">
          Verification failed at event {result.firstBreak?.sequence} in stream “{result.firstBreak?.stream}”.
        </Notice>
      ) : null}
    </div>
  );
}
