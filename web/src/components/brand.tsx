export function Logo({ className = "" }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2 ${className}`}>
      <svg aria-hidden viewBox="0 0 24 24" className="size-5">
        <path d="M12 3 22 20H2Z" fill="none" stroke="var(--color-accent)" strokeWidth="2" strokeLinejoin="round" />
        <path d="M12 3v17" stroke="var(--color-accent)" strokeWidth="2" />
      </svg>
      <span className="text-[15px] font-semibold tracking-tight">Prism</span>
    </span>
  );
}
