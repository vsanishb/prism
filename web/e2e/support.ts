import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, type Page } from "@playwright/test";

export interface SyntheticAccount {
  email: string;
  password: string;
}

/** Reads the credentials written by the local seed command. */
export function account(role: string): SyntheticAccount {
  const file = process.env.PRISM_E2E_CREDENTIALS ?? "../.data/synthetic-users.md";
  const sheet = readFileSync(path.resolve(process.cwd(), file), "utf8");
  const match = sheet.match(new RegExp(`\\| ${role} \\| (\\S+) \\| \`([^\`]+)\``));
  if (!match?.[1] || !match[2]) {
    throw new Error(`No synthetic account for ${role}. Run the seed command first.`);
  }
  return { email: match[1], password: match[2] };
}

/** For roles held by more than one seeded account, such as the second policy manager. */
export function accountByEmail(prefix: string): SyntheticAccount {
  const file = process.env.PRISM_E2E_CREDENTIALS ?? "../.data/synthetic-users.md";
  const sheet = readFileSync(path.resolve(process.cwd(), file), "utf8");
  const match = sheet.match(new RegExp(`\\| (${prefix}@\\S+) \\| \`([^\`]+)\``));
  if (!match?.[1] || !match[2]) {
    throw new Error(`No synthetic account ${prefix}. Run the seed command first.`);
  }
  return { email: match[1], password: match[2] };
}

export async function signIn(page: Page, { email, password }: SyntheticAccount) {
  await page.goto("/sign-in");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/overview/);
}

export function uniqueEmail(prefix: string): string {
  return `${prefix}.${Date.now()}@synthetic.prism.test`;
}

const DATA = path.resolve(process.cwd(), "../test-data");
export const READING_TIMEOUT = 45_000;

/** A fresh version on the Silver policy, in a far-future period so that repeated runs never overlap. */
export async function uploadFreshVersion(page: Page): Promise<void> {
  const year = 2100 + (Date.now() % 800);
  await page.goto("/policies");
  await page.getByRole("link", { name: /SYN-IND-2026-0002/ }).first().click();
  await page.getByRole("button", { name: "Add version" }).click();
  await page.getByLabel("Effective from").fill(`${year}-01-01`);
  await page.getByLabel("Effective to").fill(`${year}-12-31`);
  await page.getByLabel("Sum insured (₹)").fill("500000");
  await page.getByRole("button", { name: "Add version" }).last().click();
  const row = page.getByRole("group").filter({ hasText: `1 Jan ${year}` });
  await expect(row.getByText("No wording uploaded")).toBeVisible();
  await row.getByRole("button", { name: "Upload wording" }).click();
  await page.getByLabel("Upload policy wording").setInputFiles(path.join(DATA, "policies/family-floater-gold-v1.pdf"));
  await row.getByRole("link", { name: /knowledge/ }).click();
  // The list shows the same status badge, so wait for the version page itself.
  await expect(page).toHaveURL(/\/versions\//);
}
