import { defineConfig, devices } from "@playwright/test";

/**
 * Runs against the real stack: start the database, core, and AI services first, and seed the
 * synthetic users. See docs/test-guides/m1-secure-platform.md.
 */
const port = process.env.PRISM_E2E_PORT ?? "3000";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: `http://localhost:${port}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run start -- -p ${port}`,
    url: `http://localhost:${port}/sign-in`,
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
