import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";

const CONTROL =
  "h-9 w-full rounded-control border border-line-strong bg-surface px-3 text-sm text-ink placeholder:text-ink-faint transition-colors focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent-soft disabled:bg-subtle disabled:text-ink-muted";

interface FieldProps {
  label: string;
  htmlFor: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}

export function Field({ label, htmlFor, hint, error, children }: FieldProps) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-[13px] font-medium text-ink">
        {label}
      </label>
      {children}
      {error ? (
        <p role="alert" className="text-xs text-critical">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-ink-muted">{hint}</p>
      ) : null}
    </div>
  );
}

export function Input({ className = "", ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`${CONTROL} ${className}`} {...props} />;
}

export function Select({ className = "", children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={`${CONTROL} select-control pr-8 ${className}`} {...props}>
      {children}
    </select>
  );
}
