import path from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { REACHED_REVIEW, account, signIn } from "./support";

/**
 * Runs through the real stack, including Temporal and the AI service, with a stand-in for the model
 * provider. See docs/test-guides/m3-document-intelligence.md.
 */

const DATA = path.resolve(process.cwd(), "../test-data");
const PROCESSING_TIMEOUT = 45_000;

async function createClaim(page: Page, patient: string): Promise<void> {
  await page.goto("/claims/new");
  await page.getByLabel("Policy", { exact: true }).selectOption({ label: "SYN-FFG-2026-0001: Family Floater Gold (Synthetic)" });
  await page.getByLabel("Claimant").fill("Synthetic Policyholder One");
  await page.getByLabel("Patient").fill(patient);
  await page.getByLabel("Hospital").fill("Synthetic Care Hospital");
  await page.getByLabel("Admission date").fill("2026-03-14");
  await page.getByLabel("Discharge date").fill("2026-03-17");
  await page.getByLabel("Claimed amount").fill("106000");
  await page.getByRole("button", { name: "Create claim" }).click();
  await expect(page.getByText(`${patient} · ₹1,06,000.00`)).toBeVisible();
}

async function upload(page: Page, relative: string, type: string): Promise<void> {
  await page.getByLabel("Document type").selectOption({ label: type });
  await page.getByLabel("Upload documents").setInputFiles(path.join(DATA, relative));
  await expect(page.getByRole("link", { name: path.basename(relative) }).first()).toBeVisible();
}

async function submit(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Submit claim" }).click();
  await page.getByRole("button", { name: "Confirm submission" }).click();
}

async function expectStatus(page: Page, label: string | RegExp): Promise<void> {
  await expect(page.locator("header").getByText(label, { exact: true })).toBeVisible({ timeout: PROCESSING_TIMEOUT });
}

test("a complete claim is processed to review, with each fact traced to its source", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Asha");
  await upload(page, "claims/appendectomy/claim-form.pdf", "Claim form");
  await upload(page, "claims/appendectomy/hospital-bill.pdf", "Hospital bill");
  await upload(page, "claims/appendectomy/discharge-summary.pdf", "Discharge summary");
  await submit(page);

  await expectStatus(page, REACHED_REVIEW);
  await expect(page.getByText("Document read").first()).toBeVisible();
  await expect(page.getByText("Ready for review")).toBeVisible();

  await page.getByRole("link", { name: "Review extracted facts" }).click();
  await expect(page.getByRole("heading", { name: "Extracted facts" })).toBeVisible();

  await page.getByRole("button", { name: /Admission date/ }).first().click();
  await expect(page.getByTestId("source-highlight")).toBeVisible();
  await expect(page.getByRole("img", { name: /Page 1 of/ })).toBeVisible();

  await page.getByRole("button", { name: /Room rent/ }).click();
  await expect(page.locator("blockquote").filter({ hasText: "Room rent" })).toBeVisible();
  await expect(page.getByText("Not stated").first()).toBeHidden();
});

test("documents that disagree are shown as a conflict, with every source and no chosen value", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Asha");
  await upload(page, "claims/appendectomy/claim-form.pdf", "Claim form");
  await upload(page, "claims/conflicting/hospital-bill.pdf", "Hospital bill");
  await upload(page, "claims/conflicting/discharge-summary.pdf", "Discharge summary");
  await submit(page);
  await expectStatus(page, REACHED_REVIEW);

  await page.getByRole("link", { name: "Review extracted facts" }).click();
  await expect(page.getByText("Admission date: documents state different values. None has been chosen.")).toBeVisible();
  await expect(page.getByText("the charges add up to ₹1,05,000.00, but the stated total is ₹1,06,000.00")).toBeVisible();

  await page.getByRole("button", { name: /Admission date.*2 different values/ }).click();
  await expect(page.getByRole("button", { name: /14 Mar 2026/ })).toHaveCount(2);
  await page.getByRole("button", { name: /15 Mar 2026/ }).click();
  await expect(page.locator("blockquote").filter({ hasText: "15/03/2026" })).toBeVisible();
});

test("a claim missing documents waits, then resumes when they are added", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Asha");
  await upload(page, "claims/appendectomy/hospital-bill.pdf", "Hospital bill");
  await submit(page);

  await expectStatus(page, "Awaiting information");
  await expect(page.getByText("Waiting for documents", { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/Add claim form, discharge summary/)).toBeVisible();

  await upload(page, "claims/appendectomy/claim-form.pdf", "Claim form");
  await upload(page, "claims/appendectomy/discharge-summary.pdf", "Discharge summary");
  await expectStatus(page, REACHED_REVIEW);
});

test("a failed document is shown with its reason, and a retry finishes it", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Tara");
  await upload(page, "claims/hard/injected-bill.pdf", "Hospital bill");
  await submit(page);

  await expect(page.getByText("This step did not finish.")).toBeVisible({ timeout: PROCESSING_TIMEOUT });
  await page.getByRole("button", { name: "Retry" }).click();

  await expectStatus(page, "Awaiting information");
  await expect(page.getByText("This step did not finish.")).toBeHidden();
});

test("a scan has no highlight, and says why", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Asha");
  await upload(page, "claims/scanned/prescription.png", "Prescription");
  await submit(page);
  await expectStatus(page, "Awaiting information");

  await page.getByRole("link", { name: "Review extracted facts" }).click();
  await page.getByRole("button", { name: /Patient/ }).first().click();
  await expect(page.getByText("Scanned page: there is no text to highlight")).toBeVisible();
  await expect(page.getByTestId("source-highlight")).toHaveCount(0);
});

test("a reviewer can read facts but cannot retry, and an administrator cannot see them", async ({ browser }) => {
  const processorContext = await browser.newContext();
  const processorPage = await processorContext.newPage();
  await signIn(processorPage, account("CLAIMS_PROCESSOR"));
  await createClaim(processorPage, "Synthetic Patient Asha");
  await upload(processorPage, "claims/appendectomy/hospital-bill.pdf", "Hospital bill");
  await submit(processorPage);
  await expectStatus(processorPage, "Awaiting information");
  const claimUrl = processorPage.url();
  await processorContext.close();

  const reviewerContext = await browser.newContext();
  const reviewerPage = await reviewerContext.newPage();
  await signIn(reviewerPage, account("REVIEWER"));
  await reviewerPage.goto(`${claimUrl}/facts`);
  await expect(reviewerPage.getByRole("heading", { name: "Extracted facts" })).toBeVisible();
  await reviewerPage.goto(claimUrl);
  await expect(reviewerPage.getByRole("button", { name: "Retry" })).toHaveCount(0);
  await reviewerContext.close();

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await signIn(adminPage, account("ADMIN"));
  await adminPage.goto(`${claimUrl}/facts`);
  await expect(adminPage.getByText("You do not have access to this page")).toBeVisible();
  await adminContext.close();
});

test("the review screen fits a phone, and choosing a fact brings its source into view", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Asha");
  await upload(page, "claims/appendectomy/hospital-bill.pdf", "Hospital bill");
  await submit(page);
  await expectStatus(page, "Awaiting information");
  await page.getByRole("link", { name: "Review extracted facts" }).click();
  await expect(page.getByRole("heading", { name: "Extracted facts" })).toBeVisible();

  await page.setViewportSize({ width: 375, height: 800 });
  await page.getByRole("button", { name: /Patient/ }).first().click();
  await expect(page.getByTestId("source-highlight")).toBeInViewport({ timeout: 5_000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
