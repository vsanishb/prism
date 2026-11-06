import { defineConfig, devices } from "@playwright/test";

/**
 * Runs against the real stack: start the database, core, and AI services first, and seed the
 * synthetic users. See docs/test-guides/m1-secure-platform.md.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run start",
    url: "http://localhost:3000/sign-in",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
