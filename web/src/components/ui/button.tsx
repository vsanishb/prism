import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-accent text-white hover:bg-accent-hover",
  secondary: "bg-surface text-ink border border-line-strong hover:bg-subtle",
  ghost: "text-ink-muted hover:bg-subtle hover:text-ink",
  danger: "bg-critical text-white hover:opacity-90",
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  loading?: boolean;
}

export function Button({ variant = "primary", loading, disabled, className = "", children, ...props }: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex h-9 items-center justify-center gap-2 rounded-control px-3.5 text-[13px] font-medium whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${className}`}
      {...props}
    >
      {loading ? (
        <span aria-hidden className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
      ) : null}
      {children}
    </button>
  );
}
