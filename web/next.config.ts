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
  // The floating development badge covers the sidebar and page content.
  devIndicators: false,
  experimental: {
    // Next.js cuts proxied bodies off at 10 MB by default. This must stay above the core
    // service's multipart limit, so that uploads reach it and get its own size checks.
    proxyClientMaxBodySize: "62mb",
  },
  // Same-origin routing keeps the session cookie HttpOnly and avoids CORS entirely.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${coreBaseUrl}/api/:path*` }];
  },
};

export default config;
