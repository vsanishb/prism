import path from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { account, signIn } from "./support";

const DATA = path.resolve(process.cwd(), "../test-data");
const file = (relative: string) => path.join(DATA, relative);
const BILL = "claims/appendectomy/hospital-bill.pdf";

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

async function upload(page: Page, relative: string, type: string) {
  await page.getByLabel("Document type").selectOption({ label: type });
  await page.getByLabel("Upload documents").setInputFiles(file(relative));
}

test("a processor creates a claim, adds documents, and submits it", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Flow");

  await expect(page.getByText("Add at least one document.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Submit claim" })).toBeDisabled();
  await expect(page.getByText("Version 1: 1 Apr 2025 to 31 Mar 2026")).toBeVisible();

  await upload(page, BILL, "Hospital bill");
  await expect(page.getByRole("link", { name: "hospital-bill.pdf" })).toBeVisible();
  await upload(page, "claims/scanned/prescription.png", "Prescription");
  await expect(page.getByRole("link", { name: "prescription.png" })).toBeVisible();
  await expect(page.getByText("Ready to submit.")).toBeVisible();

  await page.getByRole("button", { name: "Submit claim" }).click();
  await page.getByRole("button", { name: "Confirm submission" }).click();

  await expect(page.getByText("Submitted", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Applicable version")).toBeVisible();
  await expect(page.getByRole("link", { name: "Edit" })).toHaveCount(0);
  await expect(page.getByText("Claim submitted")).toBeVisible();
});

test("claim form errors appear beside their fields", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await page.goto("/claims/new");
  await page.getByRole("button", { name: "Create claim" }).click();
  await expect(page.getByText("Choose a policy.")).toBeVisible();
  await expect(page.getByText("Enter the patient's name.")).toBeVisible();
  await expect(page.getByText("Enter the claimed amount.")).toBeVisible();
});

test("a discharge before admission is explained", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await page.goto("/claims/new");
  await page.getByLabel("Policy", { exact: true }).selectOption({ index: 1 });
  await page.getByLabel("Claimant").fill("Synthetic Claimant");
  await page.getByLabel("Patient").fill("Synthetic Patient");
  await page.getByLabel("Hospital").fill("Synthetic Hospital");
  await page.getByLabel("Admission date").fill("2026-03-14");
  await page.getByLabel("Discharge date").fill("2026-03-10");
  await page.getByLabel("Claimed amount").fill("1000");
  await page.getByRole("button", { name: "Create claim" }).click();
  await expect(page.getByText("The discharge date must not be before the admission date.")).toBeVisible();
});

test("unsafe files are refused with a clear reason", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Invalid");

  const cases: [string, string][] = [
    ["invalid/spoofed-text-named-as.pdf", "This file type is not supported."],
    ["invalid/truncated.pdf", "This PDF is damaged and cannot be read."],
    ["invalid/password-protected.pdf", "This PDF is password protected."],
    ["invalid/unsupported-office-file.docx", "This file type is not supported."],
  ];
  for (const [relative, message] of cases) {
    await upload(page, relative, "Other");
    await expect(page.getByText(message, { exact: false })).toBeVisible();
  }
  await expect(page.getByText("No documents yet")).toBeVisible();
  await expect(page.getByText("Upload refused").first()).toBeVisible();
});

test("a file over the limit passes the proxy and gets a clear refusal", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Oversized");
  await page.getByLabel("Upload documents").setInputFiles(path.resolve(process.cwd(), "../.data/oversized-synthetic.pdf"));
  await expect(page.getByText("This file is larger than the 25 MB limit.")).toBeVisible();
});

test("a large valid scan uploads through the proxy", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Large");
  await page.getByLabel("Upload documents").setInputFiles(path.resolve(process.cwd(), "../.data/large-valid-scan.png"));
  await expect(page.getByRole("link", { name: "large-valid-scan.png" })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(/2\d\.\d MB/)).toBeVisible();
});

test("uploading the same file twice is refused", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Duplicate");
  await upload(page, BILL, "Hospital bill");
  await expect(page.getByRole("link", { name: "hospital-bill.pdf" })).toBeVisible();
  await upload(page, BILL, "Other");
  await expect(page.getByText("This file has already been uploaded here.")).toBeVisible();
});

test("a draft document can be replaced and removed", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Replace");
  await upload(page, BILL, "Hospital bill");
  await expect(page.getByRole("link", { name: "hospital-bill.pdf" })).toBeVisible();

  await page.getByRole("button", { name: "Replace hospital-bill.pdf" }).click();
  await page.getByLabel("Replacement document").setInputFiles(file("claims/appendectomy/claim-form.pdf"));
  await expect(page.getByRole("link", { name: "claim-form.pdf" })).toBeVisible();
  await expect(page.getByText("Replaced", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Remove claim-form.pdf" }).click();
  await expect(page.getByText("Removed", { exact: true })).toBeVisible();
  await expect(page.getByText("Add at least one document.")).toBeVisible();
});

test("the viewer shows every page of a multi-page scan", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Viewer");
  await upload(page, "claims/scanned/discharge-note.tiff", "Discharge summary");
  await page.getByRole("link", { name: "discharge-note.tiff" }).click();

  await expect(page.getByText("Page 1 of 2")).toBeVisible();
  const image = page.getByRole("img", { name: /Page 1 of/ });
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);

  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("Page 2 of 2")).toBeVisible();
  await expect(page.getByRole("button", { name: "Next page" })).toBeDisabled();
  const second = page.getByRole("img", { name: /Page 2 of/ });
  await expect.poll(() => second.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);

  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download original" }).click();
  expect((await download).suggestedFilename()).toBe("discharge-note.tiff");

  await page.getByRole("link", { name: "Back" }).click();
  await expect(page).toHaveURL(/\/claims\/[0-9a-f-]+$/);
});

test("a draft can be edited", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Edit");
  await page.getByRole("link", { name: "Edit" }).click();
  await page.getByLabel("Patient").fill("Synthetic Patient Edited");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText("Synthetic Patient Edited · ₹1,06,000.00")).toBeVisible();
  await expect(page.getByText("Claim edited")).toBeVisible();
});

test("a claim can be withdrawn with a reason", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await createClaim(page, "Synthetic Patient Withdraw");
  await page.getByRole("button", { name: "Withdraw" }).click();
  await expect(page.getByRole("button", { name: "Withdraw claim" })).toBeDisabled();
  await page.getByLabel("Reason").selectOption({ label: "Duplicate entry" });
  await page.getByRole("button", { name: "Withdraw claim" }).click();
  await expect(page.getByText("Withdrawal reason")).toBeVisible();
  await expect(page.getByLabel("Upload documents")).toHaveCount(0);
});

test("the claims list filters and searches", async ({ page }) => {
  await signIn(page, account("CLAIMS_PROCESSOR"));
  await page.goto("/claims");
  await page.getByLabel("Filter by status").selectOption("WITHDRAWN");
  await expect(page).toHaveURL(/status=WITHDRAWN/);
  await expect(page.getByRole("link", { name: "CLM-2026-000002" })).toBeVisible();
  await expect(page.getByRole("link", { name: "CLM-2026-000001" })).toHaveCount(0);

  await page.goto("/claims");
  await page.getByLabel("Search claims").fill("CLM-2026-000001");
  await page.getByLabel("Search claims").press("Enter");
  await expect(page.getByRole("cell", { name: "Synthetic Patient Asha" })).toBeVisible();
  await expect(page.getByRole("link", { name: "CLM-2026-000002" })).toHaveCount(0);
});

test("a reviewer reads submitted claims, cannot change them, and cannot see drafts", async ({ page }) => {
  await signIn(page, account("REVIEWER"));
  await page.goto("/claims");
  await expect(page.getByRole("link", { name: "New claim" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "CLM-2026-000003" })).toHaveCount(0);

  await page.getByRole("link", { name: "CLM-2026-000001" }).click();
  await expect(page.getByRole("heading", { name: "CLM-2026-000001" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Withdraw" })).toHaveCount(0);
  await expect(page.getByLabel("Upload documents")).toHaveCount(0);
  await page.getByRole("link", { name: "hospital-bill.pdf" }).click();
  await expect(page.getByText("Page 1 of 1")).toBeVisible();

  await page.goto("/claims/new");
  await expect(page.getByText("You do not have access to this page")).toBeVisible();
});

test("an administrator has no access to claims", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Claims" })).toHaveCount(0);
  await page.goto("/claims");
  await expect(page.getByText("You do not have access to this page")).toBeVisible();
});

test("a policy manager registers a policy, adds versions, and uploads a wording", async ({ page }) => {
  await signIn(page, account("POLICY_MANAGER"));
  await page.goto("/policies");
  await page.getByRole("button", { name: "Register policy" }).click();
  await page.getByLabel("Policy number").fill(`SYN-E2E-${Date.now()}`);
  await page.getByLabel("Product").fill("Synthetic E2E Product");
  await page.getByLabel("Policyholder").fill("Synthetic Holder");
  await page.getByRole("dialog").getByRole("button", { name: "Register policy" }).click();
  await expect(page.getByText("No versions yet")).toBeVisible();

  const addVersion = async (from: string, to: string) => {
    await page.getByRole("button", { name: "Add version" }).click();
    await page.getByLabel("Effective from").fill(from);
    await page.getByLabel("Effective to").fill(to);
    await page.getByLabel("Sum insured").fill("500000");
    await page.getByRole("dialog").getByRole("button", { name: "Add version" }).click();
  };
  await addVersion("2026-01-01", "2026-12-31");
  await expect(page.getByText("Version 1", { exact: true })).toBeVisible();
  await expect(page.getByText("₹5,00,000.00")).toBeVisible();

  await addVersion("2026-06-01", "2027-05-31");
  await expect(page.getByText("This period overlaps an existing version of the policy.")).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();

  await page.getByRole("button", { name: "Upload wording" }).click();
  await page.getByLabel("Upload policy wording").setInputFiles(file("claims/scanned/prescription.png"));
  await expect(page.getByText("Policy wordings must be PDF files.", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "Upload wording" }).click();
  await page.getByLabel("Upload policy wording").setInputFiles(file("policies/family-floater-gold-v2.pdf"));
  await page.getByRole("link", { name: "View wording" }).click();
  await expect(page.getByText("Page 1 of 2")).toBeVisible();
});

test("an auditor sees who viewed a document", async ({ page }) => {
  await signIn(page, account("AUDITOR"));
  await page.goto("/audit?action=DOCUMENT_VIEWED");
  await expect(page.getByRole("cell", { name: "Synthetic Reviewer" }).first()).toBeVisible();
  await page.getByRole("button", { name: "Verify integrity" }).click();
  await expect(page.getByText("The audit trail is intact")).toBeVisible();
});
