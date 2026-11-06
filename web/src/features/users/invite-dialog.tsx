"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Notice } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { ApiError, apiRequest, errorMessage } from "@/lib/client-api";
import type { IssuedLink, Role } from "@/lib/types";
import { RolePicker } from "./role-picker";

export function InviteDialog({
  open,
  onOpenChange,
  onInvited,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onInvited: (issued: IssuedLink) => void;
}) {
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [roles, setRoles] = useState<Role[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      const issued = await apiRequest<IssuedLink>("POST", "/api/v1/invitations", { email, fullName, roles });
      setEmail("");
      setFullName("");
      setRoles([]);
      onInvited(issued);
    } catch (failure) {
      if (failure instanceof ApiError && failure.fields.length > 0) {
        setFieldErrors(Object.fromEntries(failure.fields.map((item) => [item.field, item.message])));
      } else {
        setError(errorMessage(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Invite a staff member" description="They will set their own password through a one-time link.">
      <form onSubmit={submit} className="space-y-4">
        {error ? <Notice tone="critical" title={error} /> : null}
        <Field label="Full name" htmlFor="invite-name" error={fieldErrors.fullName}>
          <Input id="invite-name" value={fullName} onChange={(event) => setFullName(event.target.value)} />
        </Field>
        <Field label="Work email" htmlFor="invite-email" error={fieldErrors.email}>
          <Input id="invite-email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        </Field>
        <div>
          <RolePicker value={roles} onChange={setRoles} />
          {fieldErrors.roles ? (
            <p role="alert" className="mt-1 text-xs text-critical">
              {fieldErrors.roles}
            </p>
          ) : null}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Create invitation
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
