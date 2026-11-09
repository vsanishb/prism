import path from "node:path";
import { expect, test, type Browser, type Page } from "@playwright/test";
import { READING_TIMEOUT, REACHED_REVIEW, account, accountByEmail, signIn } from "./support";

/**
 * Runs through the real stack: claim processing ends with the Policy Agent reading the claim's
 * published policy. A stand-in model answers within the agent's contract, so this proves the flow,
 * the checks, and the screens, not the quality of the analysis, which the evaluation set measures.
 * See docs/test-guides/m6-policy-agent.md.
 */

const DATA = path.resolve(process.cwd(), "../test-data");
const PROCESSING_TIMEOUT = 90_000;
const POLICY = `SYN-E2E-${Date.now()}`;

/** A new policy each run, with one version covering March 2026, published and searchable. */
async function publishedPolicy(browser: Browser): Promise<void> {
  const managerContext = await browser.newContext();
  const page = await managerContext.newPage();
  await signIn(page, account("POLICY_MANAGER"));
  await page.goto("/policies");
  await page.getByRole("button", { name: "Register policy" }).click();
  await page.getByLabel("Policy number").fill(POLICY);
  await page.getByLabel("Product").fill("Agent Test Cover");
  await page.getByLabel("Policyholder").fill("Arvind Menon");
  await page.getByRole("button", { name: "Register policy" }).last().click();
  // Registration opens the new policy's page.
  await expect(page.getByRole("heading", { name: POLICY })).toBeVisible();
  await page.getByRole("button", { name: "Add version" }).click();
  await page.getByLabel("Effective from").fill("2025-04-01");
  await page.getByLabel("Effective to").fill("2026-03-31");
  await page.getByLabel("Sum insured (₹)").fill("500000");
  await page.getByRole("button", { name: "Add version" }).last().click();
  const row = page.getByRole("group").filter({ hasText: "1 Apr 2025" });
  await row.getByRole("button", { name: "Upload wording" }).click();
  await page.getByLabel("Upload policy wording").setInputFiles(path.join(DATA, "policies/family-floater-gold-v1.pdf"));
  await row.getByRole("link", { name: /knowledge/ }).click();
  await expect(page).toHaveURL(/\/versions\//);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });
  const versionUrl = page.url();
  while ((await page.getByRole("button", { name: "Confirm", exact: true }).count()) > 0) {
    const before = await page.getByRole("button", { name: "Confirm", exact: true }).count();
    await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
    await expect(page.getByRole("button", { name: "Confirm", exact: true })).toHaveCount(before - 1);
  }
  await managerContext.close();

  const approverContext = await browser.newContext();
  const approver = await approverContext.newPage();
  await signIn(approver, accountByEmail("policy.approver"));
  await approver.goto(versionUrl);
  await approver.getByRole("button", { name: "Publish" }).click();
  await expect(approver.getByTestId("search-index-status")).toHaveText("Searchable", { timeout: READING_TIMEOUT });
  await approverContext.close();
}

async function processedClaim(page: Page, policyLabel: RegExp): Promise<void> {
  await page.goto("/claims/new");
  const policy = page.getByLabel("Policy", { exact: true });
  const option = (await policy.locator("option").allTextContents()).find((text) => policyLabel.test(text));
  await policy.selectOption({ label: option ?? "" });
  await page.getByLabel("Claimant").fill("Arvind Menon");
  await page.getByLabel("Patient").fill("Asha Kulkarni");
  await page.getByLabel("Hospital").fill("Vrinda Care Hospital");
  await page.getByLabel("Admission date").fill("2026-03-14");
  await page.getByLabel("Discharge date").fill("2026-03-17");
  await page.getByLabel("Claimed amount").fill("106000");
  await page.getByRole("button", { name: "Create claim" }).click();
  for (const [file, type] of [
    ["claims/appendectomy/claim-form.pdf", "Claim form"],
    ["claims/appendectomy/hospital-bill.pdf", "Hospital bill"],
    ["claims/appendectomy/discharge-summary.pdf", "Discharge summary"],
  ] as const) {
    await page.getByLabel("Document type").selectOption({ label: type });
    await page.getByLabel("Upload documents").setInputFiles(path.join(DATA, file));
    await expect(page.getByRole("link", { name: path.basename(file) }).first()).toBeVisible();
  }
  await page.getByRole("button", { name: "Submit claim" }).click();
  await page.getByRole("button", { name: "Confirm submission" }).click();
  await expect(page.locator("header").getByText(REACHED_REVIEW)).toBeVisible({ timeout: PROCESSING_TIMEOUT });
}

test.describe.configure({ mode: "serial" });

let claimUrl = "";

test("a claim on a published policy is analyzed issue by issue, with checked citations", async ({ browser }) => {
  test.setTimeout(240_000);
  await publishedPolicy(browser);
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await processedClaim(page, new RegExp(POLICY));
  claimUrl = page.url();

  await page.getByRole("link", { name: "Read policy analysis" }).click();
  await expect(page.getByRole("heading", { name: "Policy analysis" })).toBeVisible();
  await expect(page.getByText("This analysis assists review. It is not a claim decision.")).toBeVisible();

  // Facts, evidence, reasoning, and unknowns are kept apart for every issue.
  const coverage = page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: "Is in-patient treatment covered?" }) });
  await expect(coverage.getByText(/Supported by the wording|Limited by the wording/)).toBeVisible();
  for (const part of ["Claim facts used", "Policy evidence", "Reasoning, by AI", "Open questions"]) {
    await expect(coverage.getByRole("heading", { name: part })).toBeVisible();
  }

  // Cost sharing depends on an age the documents do not give, so it is left open, not guessed.
  const sharing = page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: /co-payment or deductible/ }) });
  await expect(sharing.getByText("Needs interpretation")).toBeVisible();
  await expect(sharing.getByText("The insured person's age is not in the claim documents.")).toBeVisible();

  // Every citation opens at its page of the wording.
  await coverage.getByRole("button", { name: /Page \d+/ }).first().click();
  await expect(page.getByTestId("source-highlight")).toBeVisible();

  // A limit the agent names is calculated by Prism from the confirmed value.
  await expect(page.getByTestId("calculation").filter({ hasText: "INR" }).first()).toBeVisible();
  await context.close();
});

test("a reviewer reads the analysis, and an administrator cannot", async ({ browser }) => {
  const reviewerContext = await browser.newContext();
  const reviewer = await reviewerContext.newPage();
  await signIn(reviewer, account("REVIEWER"));
  await reviewer.goto(`${claimUrl}/assessment`);
  await expect(reviewer.getByRole("heading", { name: "Policy analysis" })).toBeVisible();
  await expect(reviewer.getByRole("heading", { name: "Is in-patient treatment covered?" })).toBeVisible();
  await reviewerContext.close();

  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  await signIn(admin, account("ADMIN"));
  await admin.goto(`${claimUrl}/assessment`);
  await expect(admin.getByText("You do not have access to this page")).toBeVisible();
  await adminContext.close();
});

test("a claim on an unpublished policy still reaches review, and says why it was not analyzed", async ({ page }) => {
  test.setTimeout(150_000);
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await processedClaim(page, /NGI-FFG-2026-0001/);

  await expect(page.getByText("The policy version for this claim is not published yet. Publish it, then retry.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Read policy analysis" })).toHaveCount(0);
});

test("the analysis fits a phone, and choosing a citation brings its page into view", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto(`${claimUrl}/assessment`);
  await page.getByRole("button", { name: /Page \d+/ }).first().click();
  await expect(page.getByTestId("source-highlight")).toBeInViewport({ timeout: 5_000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
