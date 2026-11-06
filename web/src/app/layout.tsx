import { GeistMono } from "geist/font/mono";
import { GeistSans } from "geist/font/sans";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

// Every page needs a per-request CSP nonce, which prerendered HTML cannot carry.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: { default: "Prism", template: "%s · Prism" },
  description: "Health Insurance Claims Intelligence Platform",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en-IN" className={`${GeistSans.variable} ${GeistMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
