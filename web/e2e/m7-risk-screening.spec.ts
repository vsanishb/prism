import { expect, test, type Page } from "@playwright/test";
import { REACHED_REVIEW, account, signIn } from "./support";

/**
 * The three seeded risk demonstrations, through the real stack: seeded earlier claims, the normal
 * submission, extraction, consolidation, rules, both anomaly detectors, scoring, and the AI summary.
 * A stand-in model answers within each contract, so this proves the flow and the screens. The
 * detectors are the real ones. See docs/test-guides/m7-fraud-risk.md.
 */

const PROCESSING_TIMEOUT = 120_000;
/** Words that would turn screening into an accusation or a decision. The page's own disclaimer is removed first. */
const ACCUSATION =
  /fraudulent|fraudster|committed fraud|fraud (?:is|was|has been) (?:confirmed|proven|detected|committed)|is fraud\b|claim (?:is|was) rejected|automatically rejected/i;
const DISCLAIMER = "Only an investigator can record confirmed fraud.";

/**
 * Submits the scenario's seeded draft and waits for its screening. A scenario runs once per database,
 * so on a stack where an earlier run already submitted it, its existing screening is opened instead.
 */
async function submitScenario(page: Page, scenario: "SEVERE" | "CLEAN" | "DISGUISED"): Promise<void> {
  await page.goto("/demonstrations");
  const card = page.getByTestId(`scenario-${scenario}`);
  const submit = card.getByRole("button", { name: "Submit scenario claim" });
  const screened = card.getByRole("link", { name: "Read risk screening" });
  // Submission is enabled once the earlier claims it is compared with are processed. The page refreshes itself.
  await expect(submit.and(page.locator(":enabled")).or(screened)).toBeVisible({ timeout: PROCESSING_TIMEOUT });
  if (await submit.isVisible()) {
    await submit.click();
    await expect(page).toHaveURL(/\/claims\/[0-9a-f-]{36}$/);
    await expect(page.locator("header").getByText(REACHED_REVIEW)).toBeVisible({ timeout: PROCESSING_TIMEOUT });
    await page.getByRole("link", { name: "Read risk screening" }).click();
  } else {
    await screened.click();
  }
  await expect(page.getByRole("heading", { name: "Risk screening", level: 1 })).toBeVisible();
}

async function expectNoAccusation(page: Page): Promise<void> {
  const text = (await page.locator("main").innerText()).replace(DISCLAIMER, "");
  expect(text).not.toMatch(ACCUSATION);
}

test.describe.configure({ mode: "serial" });

const screenings: Record<string, string> = {};

test("the demonstrations page explains three scenarios and the claims they are compared with", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Risk demonstrations" }).click();
  await expect(page.getByRole("heading", { name: "Risk demonstrations" })).toBeVisible();
  for (const scenario of ["SEVERE", "CLEAN", "DISGUISED"]) {
    const card = page.getByTestId(`scenario-${scenario}`);
    await expect(card.getByText("Embedded signals")).toBeVisible();
    await expect(card.getByText("What to expect")).toBeVisible();
  }
  await expect(page.getByTestId("earlier-claim")).toHaveCount(3);
});

test("the clean scenario screens as low risk, and shows every check that found nothing", async ({ page }) => {
  test.setTimeout(240_000);
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await submitScenario(page, "CLEAN");
  screenings.CLEAN = page.url();

  await expect(page.getByTestId("risk-band")).toHaveText("Low risk");
  await expect(page.getByTestId("review-route")).toHaveText("Standard review");
  await expect(page.getByText("No rule raised an indicator")).toBeVisible();
  await expect(page.getByTestId("risk-indicator")).toHaveCount(0);
  for (const detector of ["ISOLATION_FOREST", "LOCAL_OUTLIER_FACTOR"]) {
    await expect(page.getByTestId(`detector-${detector}`).getByText("Within the normal range")).toBeVisible();
  }
  await expect(page.getByText("Nothing to summarize: no rule or detector raised a signal.")).toBeVisible();
  // Open by default for a quiet result, so the reviewer sees what was checked.
  await expect(page.getByTestId("risk-rule")).toHaveCount(22);
  await expectNoAccusation(page);
});

test("the severe scenario is high risk, with evidence behind every indicator", async ({ page }) => {
  test.setTimeout(240_000);
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await submitScenario(page, "SEVERE");
  screenings.SEVERE = page.url();

  await expect(page.getByTestId("risk-band")).toHaveText("High risk");
  await expect(page.getByTestId("review-route")).toHaveText("Senior review or investigation");
  for (const title of [
    "The patient differs across documents",
    "A bill is dated before the admission",
    "Line items do not add up to the bill's total",
    "A line item is repeated on a bill",
    "The claimed amount is more than the bills",
    "The claimed amount is above the sum insured",
    "The same file is in another claim",
    "An invoice number is reused at the same hospital",
  ]) {
    await expect(page.getByTestId("risk-indicator").filter({ hasText: title }).first()).toBeVisible();
  }
  // Every indicator rests on evidence, and a document's evidence opens its page.
  for (const indicator of await page.getByTestId("risk-indicator").all()) {
    await expect(indicator.getByText("Evidence", { exact: true })).toBeVisible();
  }
  const sameFile = page.getByTestId("risk-indicator").filter({ hasText: "The same file is in another claim" });
  await expect(sameFile.getByText("Fraud indicator")).toBeVisible();
  await expect(sameFile.getByText("It is not a finding of fraud.")).toBeVisible();
  await expect(sameFile.getByRole("link", { name: /Other claim CLM-/ })).toBeVisible();
  await page.getByTestId("risk-indicator").filter({ hasText: "Line items do not add up" }).getByRole("button").first().click();
  await expect(page.getByRole("img", { name: /Itemized invoice/ })).toBeVisible();

  for (const detector of ["ISOLATION_FOREST", "LOCAL_OUTLIER_FACTOR"]) {
    await expect(page.getByTestId(`detector-${detector}`).getByText("Statistically unusual")).toBeVisible();
  }
  // The AI summary cites signals, and a citation leads to its signal.
  const summary = page.getByTestId("risk-summary");
  await expect(summary).toContainText("S1");
  await summary.getByRole("button", { name: "Go to signal S1" }).click();
  await expect(page.locator("#signal-S1")).toBeInViewport();

  await page.getByTestId("technical-details").locator("summary").click();
  await expect(page.getByTestId("technical-details").getByText(/threshold/).first()).toBeVisible();
  await expect(page.getByTestId("score-category").filter({ hasText: "Consistency" })).toContainText("capped");
  await expectNoAccusation(page);
});

test("the disguised scenario: rules alone give medium, and both detectors raise it to high", async ({ page }) => {
  test.setTimeout(240_000);
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await submitScenario(page, "DISGUISED");
  screenings.DISGUISED = page.url();

  await expect(page.getByTestId("risk-band")).toHaveText("High risk");
  await expect(page.getByTestId("risk-score")).toHaveText("57");
  await expect(page.getByText("Rules alone give 37 (medium risk).")).toBeVisible();
  await expect(page.getByText(/moved the claim to a higher band/)).toBeVisible();
  const titles = await page.getByTestId("risk-indicator").locator("h4").allInnerTexts();
  expect(titles.map((title) => title.replace(/^S\d+\s*/, ""))).toEqual([
    "A bill closely matches a bill in another claim",
    "A similar claim shortly before or after",
  ]);
  for (const detector of ["ISOLATION_FOREST", "LOCAL_OUTLIER_FACTOR"]) {
    await expect(page.getByTestId(`detector-${detector}`).getByText("Statistically unusual")).toBeVisible();
  }
  const prior = page.getByTestId("risk-feature").filter({ hasText: "Patient's earlier claims in the past year" });
  await expect(prior).toContainText("Unusual");
  await expectNoAccusation(page);
});

test("the demonstrations page shows each result, and a reviewer can read them but an administrator cannot", async ({ browser }) => {
  const processorContext = await browser.newContext();
  const processor = await processorContext.newPage();
  await signIn(processor, account("CLAIMS_PROCESSOR"));
  await processor.goto("/demonstrations");
  await expect(processor.getByTestId("scenario-SEVERE").getByTestId("scenario-band")).toHaveText("High risk");
  await expect(processor.getByTestId("scenario-CLEAN").getByTestId("scenario-band")).toHaveText("Low risk");
  await expect(processor.getByTestId("scenario-DISGUISED").getByTestId("scenario-band")).toHaveText("High risk");
  await processorContext.close();

  const reviewerContext = await browser.newContext();
  const reviewer = await reviewerContext.newPage();
  await signIn(reviewer, account("REVIEWER"));
  await reviewer.goto(screenings.SEVERE ?? "/");
  await expect(reviewer.getByTestId("risk-band")).toHaveText("High risk");
  await expect(reviewer.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Risk demonstrations" })).toHaveCount(0);
  await reviewerContext.close();

  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  await signIn(admin, account("ADMIN"));
  await admin.goto(screenings.SEVERE ?? "/");
  await expect(admin.getByText("You do not have access to this page")).toBeVisible();
  await adminContext.close();
});

test("the risk screening fits a phone", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto(screenings.DISGUISED ?? "/");
  await expect(page.getByTestId("risk-band")).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
