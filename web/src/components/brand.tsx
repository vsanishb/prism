const SIZES = {
  md: { mark: "size-5", word: "text-base" },
  lg: { mark: "size-6", word: "text-lg" },
  xl: { mark: "size-8", word: "text-2xl" },
};

export function Logo({ className = "", size = "md" }: { className?: string; size?: keyof typeof SIZES }) {
  const { mark, word } = SIZES[size];
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <svg aria-hidden viewBox="0 0 24 24" className={mark}>
        <path d="M12 3 22 20H2Z" fill="none" stroke="var(--color-accent)" strokeWidth="2" strokeLinejoin="round" />
        <path d="M12 3v17" stroke="var(--color-accent)" strokeWidth="2" />
      </svg>
      <span className={`${word} font-semibold tracking-tight`}>Prism</span>
    </span>
  );
}
