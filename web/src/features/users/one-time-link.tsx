"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/field";
import { formatDateTime } from "@/lib/format";
import type { IssuedLink } from "@/lib/types";

/** Shows a link exactly once: only its hash is stored, so it cannot be retrieved later. */
export function OneTimeLinkDialog({ title, issued, onClose }: { title: string; issued: IssuedLink | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    if (issued) {
      await navigator.clipboard.writeText(issued.link);
      setCopied(true);
    }
  }

  return (
    <Dialog
      open={issued !== null}
      onOpenChange={(open) => {
        if (!open) {
          setCopied(false);
          onClose();
        }
      }}
      title={title}
      description="Send this link to the person securely. It is shown only once."
    >
      {issued ? (
        <div className="space-y-3">
          <Input readOnly aria-label="One-time link" value={issued.link} className="font-mono text-xs" onFocus={(event) => event.target.select()} />
          <p className="text-xs text-ink-muted">Expires {formatDateTime(issued.expiresAt)}</p>
          <div className="flex justify-end">
            <Button variant="secondary" onClick={copy}>
              {copied ? <Check aria-hidden className="size-3.5" /> : <Copy aria-hidden className="size-3.5" />}
              {copied ? "Copied" : "Copy link"}
            </Button>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}
