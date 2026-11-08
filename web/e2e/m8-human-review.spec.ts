import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Browser, type Page } from "@playwright/test";
import { REACHED_REVIEW, account, signIn } from "./support";

/**
 * Human review through the real stack: routing, the workspace, a request for information answered by
 * intake, a fact correction analyzed again, escalation, investigation, outcomes, revision, and the
 * evidence chain. See docs/test-guides/m8-human-review.md.
 */

const DATA = path.resolve(process.cwd(), "../test-data");
const PROCESSING_TIMEOUT = 120_000;

async function as(browser: Browser, role: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, account(role));
  return page;
}

/**
 * A policy of its own for each claim. Other tests file the same patient and dates on the seeded policy,
 * which screening rightly flags as overlapping stays and would route to investigation.
 */
async function freshPolicy(browser: Browser): Promise<string> {
  const number = `SYN-M8-${Date.now()}`;
  const manager = await as(browser, "POLICY_MANAGER");
  await manager.goto("/policies");
  await manager.getByRole("button", { name: "Register policy" }).click();
  await manager.getByLabel("Policy number").fill(number);
  await manager.getByLabel("Product").fill("Review Test Cover (Synthetic)");
  await manager.getByLabel("Policyholder").fill("Synthetic Policyholder One");
  await manager.getByRole("button", { name: "Register policy" }).last().click();
  await expect(manager.getByRole("heading", { name: number })).toBeVisible();
  await manager.getByRole("button", { name: "Add version" }).click();
  await manager.getByLabel("Effective from").fill("2025-04-01");
  await manager.getByLabel("Effective to").fill("2026-03-31");
  await manager.getByLabel("Sum insured (₹)").fill("500000");
  await manager.getByRole("button", { name: "Add version" }).last().click();
  await expect(manager.getByRole("group").filter({ hasText: "1 Apr 2025" })).toBeVisible();
  await manager.context().close();
  return number;
}

/**
 * The test file with a unique comment appended after its end, so its SHA-256 is new. Other tests upload
 * the same files many times, and a claim whose every document is a copy is, rightly, high risk.
 */
function uniqueCopy(file: string): { name: string; mimeType: string; buffer: Buffer } {
  const original = readFileSync(path.join(DATA, file));
  return {
    name: path.basename(file),
    mimeType: "application/pdf",
    buffer: Buffer.concat([original, Buffer.from(`
%${Date.now()}-${Math.random()}
`)]),
  };
}

/** A new claim each run, processed to review. */
async function freshClaim(page: Page, browser: Browser, amount = "106000"): Promise<string> {
  const number = await freshPolicy(browser);
  await page.goto("/claims/new");
  const policy = page.getByLabel("Policy", { exact: true });
  const option = (await policy.locator("option").allTextContents()).find((text) => text.includes(number));
  await policy.selectOption({ label: option ?? "" });
  await page.getByLabel("Claimant").fill("Synthetic Policyholder One");
  await page.getByLabel("Patient").fill("Synthetic Patient Asha");
  await page.getByLabel("Hospital").fill("Synthetic Care Hospital, Pune (Fictional)");
  await page.getByLabel("Admission date").fill("2026-03-14");
  await page.getByLabel("Discharge date").fill("2026-03-17");
  await page.getByLabel("Claimed amount").fill(amount);
  await page.getByRole("button", { name: "Create claim" }).click();
  for (const [file, type] of [
    ["claims/appendectomy/claim-form.pdf", "Claim form"],
    ["claims/appendectomy/hospital-bill.pdf", "Hospital bill"],
    ["claims/appendectomy/discharge-summary.pdf", "Discharge summary"],
  ] as const) {
    await page.getByLabel("Document type").selectOption({ label: type });
    await page.getByLabel("Upload documents").setInputFiles(uniqueCopy(file));
    await expect(page.getByRole("link", { name: path.basename(file) }).first()).toBeVisible();
  }
  await page.getByRole("button", { name: "Submit claim" }).click();
  await page.getByRole("button", { name: "Confirm submission" }).click();
  await expect(page.locator("header").getByText(REACHED_REVIEW)).toBeVisible({ timeout: PROCESSING_TIMEOUT });
  return page.url().replace(/^.*\/claims\//, "");
}

/**
 * Fresh test claims reuse the shared appendectomy documents' content, which screening rightly finds in
 * earlier test claims, so they usually go to an investigator first. An investigator who finds no
 * concern sends the claim on to reviewers, as a real one would.
 */
async function throughInvestigation(browser: Browser, claimId: string): Promise<void> {
  const investigator = await as(browser, "FRAUD_INVESTIGATOR");
  await workspace(investigator, claimId);
  if ((await investigator.getByTestId("task-status").filter({ hasText: "High risk: investigation first" }).count()) > 0) {
    await investigator.getByRole("button", { name: "Take task" }).click();
    await investigator.getByRole("button", { name: "Record finding" }).click();
    await investigator.getByLabel("Finding", { exact: true }).selectOption({ label: "No concern found" });
    await investigator.getByLabel("Rationale").fill("The matching bills belong to separate, genuine test admissions.");
    await investigator.getByRole("button", { name: "Record finding" }).last().click();
    await expect(investigator.getByTestId("task-status")).toContainText("Investigation finished");
  }
  await investigator.context().close();
}

async function workspace(page: Page, claimId: string): Promise<void> {
  await page.goto(`/claims/${claimId}/review`);
  await expect(page.getByRole("heading", { name: "Claim facts" })).toBeVisible();
}

async function recordOutcome(page: Page, decision: string, rationale: string, amount?: string): Promise<void> {
  await page.getByRole("button", { name: "Record outcome" }).click();
  await page.getByRole("radio", { name: new RegExp(`^${decision}`) }).check();
  if (amount) {
    await page.getByLabel("Approved amount").fill(amount);
  }
  await page.getByLabel("Rationale").fill(rationale);
  await page.getByLabel("The AI analysis").selectOption({ label: "It partly agreed" });
  await page.getByRole("dialog").getByRole("button", { name: "Record outcome" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}

test.describe.configure({ mode: "serial" });

let journeyClaim = "";

test("a reviewer takes a task, asks for a document, and works the claim when intake answers", async ({ browser }) => {
  test.setTimeout(360_000);
  const processor = await as(browser, "CLAIMS_PROCESSOR");
  journeyClaim = await freshClaim(processor, browser);
  await throughInvestigation(browser, journeyClaim);

  const reviewer = await as(browser, "REVIEWER");
  await reviewer.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Review" }).click();
  const row = reviewer.getByTestId("task-row").filter({ has: reviewer.locator(`a[href="/claims/${journeyClaim}/review"]`) });
  await row.getByRole("button", { name: "Take" }).click();
  await expect(reviewer).toHaveURL(new RegExp(`/claims/${journeyClaim}/review`));
  await expect(reviewer.getByTestId("task-status")).toContainText("Taken by you");
  for (const panel of ["Claim facts", "Policy analysis, by AI", "Risk screening", "Open questions", "Review history"]) {
    await expect(reviewer.getByRole("heading", { name: panel })).toBeVisible();
  }
  // Any fact opens its source page in one step.
  await reviewer.getByTestId("workspace-fact").filter({ hasText: "Admission date" }).getByRole("button", { name: "Source" }).click();
  await expect(reviewer.getByTestId("source-highlight")).toBeVisible();

  await reviewer.getByRole("button", { name: "Request information" }).click();
  await reviewer.getByRole("checkbox", { name: "Prescription" }).check();
  await reviewer.getByLabel("Message to intake").fill("Please add the prescription given at discharge.");
  await reviewer.getByRole("button", { name: "Send request" }).click();
  await expect(reviewer.getByText("Waiting for prescription")).toBeVisible();

  await processor.goto(`/claims/${journeyClaim}`);
  await expect(processor.getByTestId("information-request")).toContainText("Please add the prescription given at discharge.");
  await processor.getByLabel("Document type").selectOption({ label: "Prescription" });
  await processor.getByLabel("Upload documents").setInputFiles(path.join(DATA, "claims/scanned/prescription.png"));
  await expect(processor.locator("header").getByText(REACHED_REVIEW)).toBeVisible({ timeout: PROCESSING_TIMEOUT });

  await expect(async () => {
    await workspace(reviewer, journeyClaim);
    await expect(reviewer.getByTestId("task-status")).toContainText("Requested information received", { timeout: 2_000 });
  }).toPass({ timeout: PROCESSING_TIMEOUT });
  await reviewer.getByRole("button", { name: "Take task" }).click();
  await expect(reviewer.getByTestId("task-status")).toContainText("Taken by you");

  // A correction is a new version, applied when the reviewer chooses to analyze again.
  await reviewer.getByRole("button", { name: "Correct Diagnosis" }).click();
  await reviewer.getByLabel("Corrected value").fill("Acute appendicitis, confirmed at surgery");
  await reviewer.getByLabel("Why").fill("The operation note confirms the diagnosis.");
  await reviewer.getByRole("button", { name: "Save correction" }).click();
  await expect(reviewer.getByTestId("pending-corrections")).toContainText("Acute appendicitis, confirmed at surgery");
  await reviewer.getByRole("button", { name: "Analyze again with corrections" }).click();
  // The claim is processed again, then returns to the queue. The workspace does not poll, so reload.
  await expect(async () => {
    await reviewer.reload();
    await expect(reviewer.getByTestId("task-status")).toContainText("Analyzed again with corrections", { timeout: 2_000 });
  }).toPass({ timeout: PROCESSING_TIMEOUT });
  await expect(reviewer.getByTestId("workspace-fact").filter({ hasText: "Diagnosis" })).toContainText("Corrected");

  await reviewer.getByRole("button", { name: "Take task" }).click();
  await recordOutcome(reviewer, "Partly approved", "Covered, less the non-payable consumables.", "100000");
  await expect(reviewer.getByTestId("outcome").first()).toContainText("Partly approved");
  await expect(reviewer.locator("header").getByText("Decided", { exact: true })).toBeVisible();
});

test("the evidence chain traces the outcome back to source pages and AI runs", async ({ browser }) => {
  const auditor = await as(browser, "AUDITOR");
  await auditor.goto(`/claims/${journeyClaim}/evidence`);
  await expect(auditor.getByTestId("chain-decision")).toHaveText("Partly approved");
  await expect(auditor.getByTestId("chain-link")).toHaveCount(6);
  await expect(auditor.getByTestId("chain-fact").filter({ hasText: "Diagnosis" })).toContainText("Corrected by a reviewer");
  await auditor.getByTestId("chain-fact").filter({ hasText: "Admission date" }).getByRole("button").first().click();
  await expect(auditor.getByTestId("source-highlight")).toBeVisible();
  await expect(auditor.getByTestId("chain-document")).toHaveCount(4);
  await expect(auditor.getByRole("heading", { name: "AI runs" })).toBeVisible();

  const admin = await as(browser, "ADMIN");
  await admin.goto(`/claims/${journeyClaim}/evidence`);
  await expect(admin.getByText("You do not have access to this page")).toBeVisible();
});

test("a senior reviewer revises an outcome with a new record, and the original stays", async ({ browser }) => {
  const senior = await as(browser, "SENIOR_REVIEWER");
  await workspace(senior, journeyClaim);
  await senior.getByRole("button", { name: "Revise outcome" }).click();
  await senior.getByRole("radio", { name: /^Approved/ }).check();
  await senior.getByLabel("Rationale").fill("The consumables are payable under the surgical package.");
  await senior.getByLabel("The AI analysis").selectOption({ label: "The AI analysis agreed with my decision" });
  await senior.getByRole("button", { name: "Record revision" }).click();
  await expect(senior.getByTestId("outcome")).toHaveCount(2);
  await expect(senior.getByTestId("outcome").first()).toContainText("Approved");
  await expect(senior.getByTestId("outcome").last()).toContainText("Superseded");
});

test("an escalated claim reaches a senior reviewer with the reason, and they decide", async ({ browser }) => {
  test.setTimeout(240_000);
  const processor = await as(browser, "CLAIMS_PROCESSOR");
  const claim = await freshClaim(processor, browser);
  await throughInvestigation(browser, claim);
  const reviewer = await as(browser, "REVIEWER");
  await workspace(reviewer, claim);
  await reviewer.getByRole("button", { name: "Take task" }).click();
  await reviewer.getByRole("button", { name: "Escalate" }).click();
  await reviewer.getByLabel("Why").fill("Room charges look high for the stated ward.");
  await reviewer.getByRole("dialog").getByRole("button", { name: "Escalate" }).click();
  await expect(reviewer.getByTestId("task-status")).toContainText("Senior review");
  // A reviewer cannot take a senior task.
  await expect(reviewer.getByRole("button", { name: "Take task" })).toHaveCount(0);

  const senior = await as(browser, "SENIOR_REVIEWER");
  await workspace(senior, claim);
  await expect(senior.getByText("Room charges look high for the stated ward.")).toBeVisible();
  await expect(senior.locator("header").getByText("Escalated", { exact: true })).toBeVisible();
  await senior.getByRole("button", { name: "Take task" }).click();
  await recordOutcome(senior, "Denied", "Charges are not supported by the ward records.");
  await expect(senior.getByTestId("outcome").first()).toContainText("Denied");
});

test("a high-risk claim goes to an investigator first, whose finding a senior reviewer decides on", async ({ browser }) => {
  test.setTimeout(300_000);
  const processor = await as(browser, "CLAIMS_PROCESSOR");
  await processor.goto("/demonstrations");
  const card = processor.getByTestId("scenario-SEVERE");
  const submit = card.getByRole("button", { name: "Submit scenario claim" });
  const done = card.getByRole("link", { name: /Read risk screening|Open claim/ });
  await expect(submit.and(processor.locator(":enabled")).or(done)).toBeVisible({ timeout: PROCESSING_TIMEOUT });
  if (await submit.isVisible()) {
    await submit.click();
    await expect(processor.locator("header").getByText("Escalated", { exact: true })).toBeVisible({ timeout: PROCESSING_TIMEOUT });
  } else {
    await done.click();
  }
  await expect(processor).toHaveURL(/\/claims\/[0-9a-f-]{36}/);
  const claim = (/\/claims\/([0-9a-f-]{36})/.exec(processor.url()) ?? [])[1] ?? "";

  const investigator = await as(browser, "FRAUD_INVESTIGATOR");
  await workspace(investigator, claim);
  if ((await investigator.getByTestId("outcome").count()) > 0) {
    // A scenario runs once per database: an earlier run decided it. Its record must still read correctly.
    await expect(investigator.getByTestId("investigation-finding")).toContainText("Fraud confirmed by investigation");
    return;
  }
  await expect(investigator.getByTestId("task-status")).toContainText("High risk: investigation first");
  const reviewer = await as(browser, "REVIEWER");
  await workspace(reviewer, claim);
  await expect(reviewer.getByRole("button", { name: "Take task" })).toHaveCount(0);

  await investigator.getByRole("button", { name: "Take task" }).click();
  await expect(investigator.getByRole("button", { name: "Record outcome" })).toHaveCount(0);
  await investigator.getByRole("button", { name: "Record finding" }).click();
  await investigator.getByLabel("Finding", { exact: true }).selectOption({ label: "Fraud confirmed by investigation" });
  await investigator.getByLabel("Rationale").fill("The hospital confirmed the bill belongs to another patient's stay.");
  await investigator.getByRole("button", { name: "Record finding" }).last().click();
  await expect(investigator.getByTestId("task-status")).toContainText("Senior review");

  const senior = await as(browser, "SENIOR_REVIEWER");
  await workspace(senior, claim);
  await expect(senior.getByTestId("task-status")).toContainText("Investigation finished");
  await senior.getByRole("button", { name: "Take task" }).click();
  await recordOutcome(senior, "Denied", "The investigation confirmed the bill is not for this patient's treatment.");
  await expect(senior.getByTestId("investigation-finding")).toContainText("Fraud confirmed by investigation");
});

test("queues follow permissions: intake has no review, and a large claim is a senior's", async ({ browser }) => {
  const processor = await as(browser, "CLAIMS_PROCESSOR");
  await expect(processor.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Review" })).toHaveCount(0);
  await processor.goto("/review");
  await expect(processor.getByText("You do not have access to this page")).toBeVisible();

  // The disguised scenario's second earlier claim is above the reviewer authority limit, and screened low.
  await processor.goto("/demonstrations");
  const href = await processor.getByTestId("earlier-claim").nth(2).getAttribute("href");
  const senior = await as(browser, "SENIOR_REVIEWER");
  await senior.goto(`${href}/review`);
  await expect(senior.getByTestId("task-status").or(senior.getByTestId("outcome").first())).toBeVisible();
  if ((await senior.getByTestId("task-status").count()) > 0) {
    await expect(senior.getByTestId("task-status")).toContainText("Above the reviewer authority limit");
  }
});

test("the workspace fits a phone", async ({ browser }) => {
  const reviewer = await as(browser, "REVIEWER");
  await reviewer.setViewportSize({ width: 375, height: 800 });
  await reviewer.goto(`/claims/${journeyClaim}/review`);
  await expect(reviewer.getByRole("heading", { name: "Claim facts" })).toBeVisible();
  const overflow = await reviewer.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
