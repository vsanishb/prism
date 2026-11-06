import path from "node:path";
import { loadEnvConfig } from "@next/env";
import type { NextConfig } from "next";

// Local development shares the repository .env. Deployed environments use real variables.
// The reload is forced because Next.js has already cached its own load of the web directory.
loadEnvConfig(path.resolve(process.cwd(), ".."), process.env.NODE_ENV !== "production", console, true);

const coreBaseUrl = process.env.PRISM_CORE_BASE_URL;
if (!coreBaseUrl) {
  throw new Error("PRISM_CORE_BASE_URL is required");
}

const config: NextConfig = {
  poweredByHeader: false,
  // Same-origin routing keeps the session cookie HttpOnly and avoids CORS entirely.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${coreBaseUrl}/api/:path*` }];
  },
};

export default config;
