import type { ReactNode, TdHTMLAttributes, ThHTMLAttributes } from "react";

export function Table({ children }: { children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left text-sm">{children}</table>
    </div>
  );
}

export function Th({ className = "", children, ...props }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th scope="col" className={`border-b border-line px-5 py-3 text-xs font-medium tracking-wide text-ink-muted uppercase ${className}`} {...props}>
      {children}
    </th>
  );
}

export function Td({ className = "", children, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td className={`border-b border-line px-5 py-3.5 align-middle ${className}`} {...props}>
      {children}
    </td>
  );
}
