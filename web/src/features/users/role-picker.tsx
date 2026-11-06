"use client";

import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "@/lib/labels";
import { ROLES, type Role } from "@/lib/types";

export function RolePicker({ value, onChange }: { value: Role[]; onChange: (roles: Role[]) => void }) {
  function toggle(role: Role) {
    onChange(value.includes(role) ? value.filter((item) => item !== role) : [...value, role]);
  }

  return (
    <fieldset className="space-y-1">
      <legend className="mb-1.5 text-[13px] font-medium">Roles</legend>
      {ROLES.map((role) => (
        <label key={role} className="flex cursor-pointer gap-2.5 rounded-control p-2 transition-colors hover:bg-subtle">
          <input
            type="checkbox"
            checked={value.includes(role)}
            onChange={() => toggle(role)}
            className="mt-0.5 size-4 accent-[var(--color-accent)]"
          />
          <span>
            <span className="block text-[13px] font-medium">{ROLE_LABELS[role]}</span>
            <span className="block text-xs text-ink-muted">{ROLE_DESCRIPTIONS[role]}</span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}
