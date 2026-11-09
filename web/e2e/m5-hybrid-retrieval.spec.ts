import { expect, test, type Browser } from "@playwright/test";
import { READING_TIMEOUT, account, accountByEmail, signIn, uploadFreshVersion } from "./support";

/**
 * Runs through the real stack: publication starts indexing in the workflow engine, and search runs
 * in the AI service against PostgreSQL with pgvector. A stand-in serves embeddings and reranking,
 * so this proves the flow, not search quality, which the evaluation set measures.
 * See docs/test-guides/m5-hybrid-retrieval.md.
 */

const INDEXING_TIMEOUT = 45_000;

/** Uploads, confirms, and publishes a fresh Gold wording. Returns the version page's URL. */
async function publishFreshVersion(browser: Browser): Promise<string> {
  const uploaderContext = await browser.newContext();
  const page = await uploaderContext.newPage();
  await signIn(page, account("POLICY_MANAGER"));
  await uploadFreshVersion(page);
  await expect(page.getByText("In review", { exact: true }).first()).toBeVisible({ timeout: READING_TIMEOUT });
  const versionUrl = page.url();
  while ((await page.getByRole("button", { name: "Confirm", exact: true }).count()) > 0) {
    const before = await page.getByRole("button", { name: "Confirm", exact: true }).count();
    await page.getByRole("button", { name: "Confirm", exact: true }).first().click();
    await expect(page.getByRole("button", { name: "Confirm", exact: true })).toHaveCount(before - 1);
  }
  await uploaderContext.close();

  const approverContext = await browser.newContext();
  const approver = await approverContext.newPage();
  await signIn(approver, accountByEmail("policy.approver"));
  await approver.goto(versionUrl);
  await approver.getByRole("button", { name: "Publish" }).click();
  await expect(approver.getByText(/Published by Deepak Menon/)).toBeVisible();
  await approverContext.close();
  return versionUrl;
}

test.describe.configure({ mode: "serial" });

let versionUrl = "";

test("publishing a version indexes it for search", async ({ browser }) => {
  // Reading, review, publication, and indexing all run here, through the workflow engine.
  test.setTimeout(150_000);
  versionUrl = await publishFreshVersion(browser);
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, account("REVIEWER"));
  await page.goto(versionUrl);

  await expect(page.getByTestId("search-index-status")).toHaveText("Searchable", { timeout: INDEXING_TIMEOUT });
  await expect(page.getByText(/passages indexed/)).toBeVisible();
  await context.close();
});

test("a reader searches a version, compares the stages, and opens a related exception at its page", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await page.goto(versionUrl);
  await page.getByRole("link", { name: "Search this version" }).click();
  await expect(page.getByRole("heading", { name: "Search policy wording" })).toBeVisible();

  // Too short a search is caught before anything is sent.
  await page.getByLabel("Question or words").fill("a");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page.getByText("Enter at least two characters.")).toBeVisible();

  // A clause named by number comes first, whatever the other stages thought.
  await page.getByLabel("Question or words").fill("what does clause 3.2 say");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const results = page.getByRole("list").filter({ has: page.getByTestId("stage-ranks") });
  const first = results.getByRole("listitem").first();
  await expect(first.getByText("Named in your search")).toBeVisible();
  await expect(first.getByRole("button").first()).toContainText("3.2");
  await expect(page.getByTestId("source-highlight")).toBeVisible();

  // A search in the wording's own words is found by both stages.
  await page.getByLabel("Question or words").fill("cosmetic or aesthetic treatment");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const cosmetic = results.getByRole("listitem").filter({ hasText: "Cosmetic" }).first();
  await expect(cosmetic.getByTestId("stage-ranks")).toContainText(/Meaning #\d+ · Words #\d+/);

  // Each stage's ranking can be compared with the final one.
  await page.getByRole("tab", { name: "Compare stages" }).click();
  const table = page.getByRole("table");
  await expect(table.getByRole("columnheader", { name: "Meaning" })).toBeVisible();
  await expect(table.getByRole("columnheader", { name: "Words" })).toBeVisible();
  await expect(table.getByRole("row").filter({ hasText: "4.1 Cosmetic treatment" })).toBeVisible();
  await page.getByRole("tab", { name: /Results/ }).click();

  // A benefit brings what limits it: the cataract waiting period, opened at its page.
  await page.getByLabel("Question or words").fill("cataract surgery");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const cataract = results.getByRole("listitem").filter({ hasText: "Cataract" }).first();
  const related = cataract.getByRole("list", { name: /Related to/ }).getByRole("button").first();
  await expect(related).toContainText("3.2");
  await related.click();
  await expect(page.getByText(/^3\.2 .* · Page \d+$/)).toBeVisible();
  await expect(page.getByTestId("source-highlight")).toBeVisible();
});

test("a search can be limited to some kinds of provision", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await page.goto(`${versionUrl}/search`);
  await page.getByText("Waiting periods", { exact: true }).click();
  await page.getByLabel("Question or words").fill("cataract surgery");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const results = page.getByRole("list").filter({ has: page.getByTestId("stage-ranks") });
  await expect(results.getByRole("listitem").first()).toBeVisible();
  for (const item of await results.getByRole("listitem").filter({ has: page.getByTestId("stage-ranks") }).all()) {
    await expect(item.getByText("Waiting periods", { exact: true })).toBeVisible();
  }
});

test("the explorer fits a phone, and choosing a result brings its page into view", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await page.goto(`${versionUrl}/search`);
  await page.setViewportSize({ width: 375, height: 800 });
  await page.getByLabel("Question or words").fill("clause 3.2");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  const results = page.getByRole("list").filter({ has: page.getByTestId("stage-ranks") });
  await results.getByRole("listitem").first().getByRole("button").first().click();
  await expect(page.getByTestId("source-highlight")).toBeInViewport({ timeout: 5_000 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test("an administrator cannot search policies", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await page.goto(`${versionUrl}/search`);
  await expect(page.getByText("You do not have access to this page")).toBeVisible();
});
