import { expect, test, type Page } from "@playwright/test";
import { READING_TIMEOUT, account, accountByEmail, signIn, uploadFreshVersion } from "./support";

/**
 * Runs through the real stack, with a stand-in for the model provider that reads the Gold wording.
 * See docs/test-guides/m4-policy-intelligence.md.
 */

async function openGoldVersion(page: Page, version: 1 | 2): Promise<void> {
  await page.goto("/policies");
  await page.getByRole("link", { name: /SYN-FFG-2026-0001/ }).first().click();
  await page.getByRole("group", { name: `Version ${version}`, exact: true }).getByRole("link", { name: /knowledge/ }).click();
  await expect(page.getByRole("heading", { name: `Version ${version}` })).toBeVisible();
}

test("a seeded wording is read into reviewable knowledge, traced to its pages", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await openGoldVersion(page, 1);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });

  await page.getByRole("tab", { name: /Structure/ }).click();
  await expect(page.getByText("Exception: except reconstruction following an accident")).toBeVisible();
  await expect(page.getByText(/Conditions in 3\.2/)).toBeVisible();

  await page.getByRole("tab", { name: /Values to confirm/ }).click();
  await page.getByRole("button", { name: /Cataract surgery · Limit/ }).click();
  await expect(page.getByTestId("source-highlight")).toBeVisible();
  await expect(page.locator("blockquote").filter({ hasText: "INR 40,000" })).toBeVisible();

  // Reading is not the same as deciding: a reviewer may look, but not confirm or publish.
  await expect(page.getByRole("button", { name: "Confirm", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Correct", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Publish" })).toHaveCount(0);
});

test("a new wording is read, confirmed, corrected, and published by a second manager", async ({ browser }) => {
  const uploaderContext = await browser.newContext();
  const page = await uploaderContext.newPage();
  await signIn(page, account("POLICY_MANAGER"));
  await uploadFreshVersion(page);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });
  const versionUrl = page.url();

  // A correction needs a reason, and a reasoned one is kept on record.
  await page.getByRole("button", { name: "Correct" }).first().click();
  await page.getByLabel("Correct value").first().fill("500000");
  await page.getByRole("button", { name: "Save correction" }).click();
  await expect(page.getByText("Explain the correction")).toBeVisible();
  await page.getByLabel("Reason").first().fill("Matches the schedule on page 1");
  await page.getByRole("button", { name: "Save correction" }).click();
  await expect(page.getByText(/Corrected from .*: Matches the schedule on page 1/)).toBeVisible();

  while ((await page.getByRole("button", { name: "Confirm", exact: true }).count()) > 0) {
    const before = await page.getByRole("button", { name: "Confirm", exact: true }).count();
    await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
    await expect(page.getByRole("button", { name: "Confirm", exact: true })).toHaveCount(before - 1);
  }
  await expect(page.getByText("Ready to publish.")).toBeVisible();

  // Separation of duties: the person who uploaded the wording cannot publish it.
  await page.getByRole("button", { name: "Publish" }).click();
  await expect(page.getByText("The person who uploaded the wording cannot publish it.")).toBeVisible();
  await uploaderContext.close();

  const approverContext = await browser.newContext();
  const approver = await approverContext.newPage();
  await signIn(approver, accountByEmail("policy.approver"));
  await approver.goto(versionUrl);
  await approver.getByRole("button", { name: "Publish" }).click();
  await expect(approver.getByText(/Published by Synthetic Policy Approver/)).toBeVisible();
  await expect(approver.getByRole("button", { name: "Confirm again", exact: true })).toHaveCount(0);
  await expect(approver.getByRole("button", { name: "Correct", exact: true })).toHaveCount(0);
  await approverContext.close();
});

test("a version can be rejected with a reason", async ({ page }) => {
  await signIn(page, account("POLICY_MANAGER"));
  await uploadFreshVersion(page);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });

  await page.getByRole("button", { name: "Reject" }).click();
  await page.getByRole("button", { name: "Reject version" }).click();
  await expect(page.getByText("Give a reason for rejecting this version.")).toBeVisible();
  await page.getByLabel("Reason").fill("Wrong wording uploaded");
  await page.getByRole("button", { name: "Reject version" }).click();
  await expect(page.getByText("Wrong wording uploaded")).toBeVisible();
});

test("an administrator cannot see policy knowledge", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await page.goto("/policies");
  await expect(page.getByText("You do not have access to this page")).toBeVisible();
});

test("the review screen fits a phone, and choosing a value brings its page into view", async ({ page }) => {
  await signIn(page, account("POLICY_MANAGER"));
  await openGoldVersion(page, 1);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });

  await page.setViewportSize({ width: 375, height: 800 });
  await page.getByRole("button", { name: /Cataract surgery · Limit/ }).click();
  await expect(page.getByTestId("source-highlight")).toBeInViewport({ timeout: 5_000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
