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
