"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Notice } from "@/components/ui/feedback";
import { apiRequest, errorMessage } from "@/lib/client-api";
import type { Role, User } from "@/lib/types";
import { RolePicker } from "./role-picker";

export function EditRolesDialog({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: () => void }) {
  const [roles, setRoles] = useState<Role[]>(user.roles);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await apiRequest("PUT", `/api/v1/users/${user.id}/roles`, { roles });
      onSaved();
    } catch (failure) {
      setError(errorMessage(failure));
      setBusy(false);
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title={`Roles for ${user.fullName}`} description="Saving ends this person's active sessions, so that the change applies at once.">
      <div className="space-y-4">
        {error ? <Notice tone="critical" title={error} /> : null}
        <RolePicker value={roles} onChange={setRoles} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={busy} onClick={save}>
            Save roles
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
