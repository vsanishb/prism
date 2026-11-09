import { expect, test, type Page } from "@playwright/test";
import { account, signIn, uniqueEmail } from "./support";

const PASSPHRASE = "synthetic staff passphrase 42";

async function inviteUser(page: Page, name: string, email: string, role: string): Promise<string> {
  await page.goto("/admin/users");
  await page.getByRole("button", { name: "Invite", exact: true }).click();
  await page.getByLabel("Full name").fill(name);
  await page.getByLabel("Work email").fill(email);
  await page.getByRole("checkbox", { name: new RegExp(`^${role}`) }).check();
  await page.getByRole("button", { name: "Create invitation" }).click();
  const link = await page.getByLabel("One-time link").inputValue();
  await page.getByRole("button", { name: "Close" }).click();
  return link;
}

async function acceptInvitation(page: Page, link: string) {
  await page.goto(link);
  await expect(page.getByRole("heading", { name: "Set up your account" })).toBeVisible();
  await page.getByLabel("Password", { exact: true }).fill(PASSPHRASE);
  await page.getByLabel("Confirm password").fill(PASSPHRASE);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText("Your account is ready")).toBeVisible();
}

test("visitors without a session are sent to sign-in", async ({ page }) => {
  await page.goto("/audit");
  await expect(page).toHaveURL(/\/sign-in/);
});

test("wrong credentials show one generic message", async ({ page }) => {
  await page.goto("/sign-in");
  await page.getByLabel("Email").fill(uniqueEmail("nobody"));
  await page.getByLabel("Password").fill("not-the-right-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Email or password is incorrect.")).toBeVisible();
});

test("the session cookie cannot be read by page scripts", async ({ page, context }) => {
  await signIn(page, account("AUDITOR"));
  const cookie = (await context.cookies()).find((item) => item.name === "prism_session");
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Lax");
  expect(await page.evaluate(() => document.cookie)).not.toContain("prism_session");
});

test("pages carry a nonce-based content security policy", async ({ page }) => {
  const response = await page.goto("/sign-in");
  const policy = response?.headers()["content-security-policy"] ?? "";
  expect(policy).toContain("script-src 'self' 'nonce-");
  expect(policy).not.toContain("unsafe-eval");
  expect(policy).toContain("frame-ancestors 'none'");
});

test("an invited person sets a password, signs in, and sees only their own access", async ({ page, browser }) => {
  await signIn(page, account("ADMIN"));
  const email = uniqueEmail("invited.reviewer");
  const link = await inviteUser(page, "Nikhil Bansal", email, "Reviewer");
  await expect(page.getByText(email)).toBeVisible();

  const fresh = await browser.newContext();
  const invitee = await fresh.newPage();
  await acceptInvitation(invitee, link);
  expect(invitee.url()).not.toContain("token=");

  await signIn(invitee, { email, password: PASSPHRASE });
  await expect(invitee.getByRole("heading", { name: "Welcome, Nikhil Bansal" })).toBeVisible();
  const navigation = invitee.getByRole("navigation", { name: "Main" });
  await expect(navigation.getByRole("link", { name: "Overview" })).toBeVisible();
  await expect(navigation.getByRole("link", { name: "Users" })).toHaveCount(0);
  await expect(navigation.getByRole("link", { name: "Audit trail" })).toHaveCount(0);

  await invitee.goto("/admin/users");
  await expect(invitee.getByText("You do not have access to this page")).toBeVisible();
  await fresh.close();
});

test("an invitation link cannot be used twice", async ({ page, browser }) => {
  await signIn(page, account("ADMIN"));
  const link = await inviteUser(page, "Manish Arora", uniqueEmail("one.use"), "Auditor");

  const fresh = await browser.newContext();
  const invitee = await fresh.newPage();
  await acceptInvitation(invitee, link);
  await invitee.goto("about:blank");
  await invitee.goto(link);
  await expect(invitee.getByText("This link cannot be used")).toBeVisible();
  await fresh.close();
});

test("a weak password is refused with a clear reason", async ({ page, browser }) => {
  await signIn(page, account("ADMIN"));
  const link = await inviteUser(page, "Varun Kohli", uniqueEmail("weak"), "Auditor");

  const fresh = await browser.newContext();
  const invitee = await fresh.newPage();
  await invitee.goto(link);
  await invitee.getByLabel("Password", { exact: true }).fill("password1234");
  await invitee.getByLabel("Confirm password").fill("password1234");
  await invitee.getByRole("button", { name: "Create account" }).click();
  await expect(invitee.getByText("This password is too common.", { exact: false })).toBeVisible();
  await fresh.close();
});

test("invitation validation errors appear beside their fields", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await page.goto("/admin/users");
  await page.getByRole("button", { name: "Invite", exact: true }).click();
  await page.getByRole("button", { name: "Create invitation" }).click();
  await expect(page.getByText("Choose at least one role.")).toBeVisible();
  await expect(page.getByText("Enter a name.")).toBeVisible();
});

test("an administrator cannot remove their own access", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await page.goto("/admin/users?search=admin@prism");
  await page.getByRole("button", { name: "Actions for Karan Malhotra" }).click();
  await page.getByRole("menuitem", { name: "Deactivate" }).click();
  await expect(page.getByText("You cannot remove your own administrator access.")).toBeVisible();
});

test("changing a user's role ends their session and applies at once", async ({ page, browser }) => {
  await signIn(page, account("ADMIN"));
  const email = uniqueEmail("role.change");
  const link = await inviteUser(page, "Ritu Saxena", email, "Reviewer");
  const fresh = await browser.newContext();
  const user = await fresh.newPage();
  await acceptInvitation(user, link);
  await signIn(user, { email, password: PASSPHRASE });

  await page.goto(`/admin/users?search=${encodeURIComponent(email)}`);
  await page.getByRole("button", { name: "Actions for Ritu Saxena" }).click();
  await page.getByRole("menuitem", { name: "Change roles" }).click();
  await page.getByRole("checkbox", { name: /^Auditor/ }).check();
  await page.getByRole("button", { name: "Save roles" }).click();
  await expect(page.getByRole("row", { name: /Ritu Saxena/ }).getByText("Auditor")).toBeVisible();

  await user.goto("/overview");
  await expect(user).toHaveURL(/\/sign-in/);
  await signIn(user, { email, password: PASSPHRASE });
  await expect(user.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Audit trail" })).toBeVisible();
  await fresh.close();
});

test("an auditor sees recorded events and verifies the chain", async ({ page }) => {
  await signIn(page, account("AUDITOR"));
  await page.goto("/audit");
  await expect(page.getByRole("heading", { name: "Audit trail" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Sign-in" }).first()).toBeVisible();

  await page.getByRole("button", { name: "Verify integrity" }).click();
  await expect(page.getByText("The audit trail is intact")).toBeVisible();

  await page.getByLabel("Filter by outcome").selectOption("FAILURE");
  await expect(page).toHaveURL(/outcome=FAILURE/);
  await page.getByRole("button", { name: "Sign-in" }).first().click();
  await expect(page.getByRole("dialog")).toContainText("invalid_credentials");
});

test("an administrator does not get audit access", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await expect(page.getByRole("link", { name: "Audit trail" })).toHaveCount(0);
  await page.goto("/audit");
  await expect(page.getByText("You do not have access to this page")).toBeVisible();
});

test("system status shows live checks between services", async ({ page }) => {
  await signIn(page, account("ADMIN"));
  await page.goto("/system");
  await expect(page.getByText("All checks passing")).toBeVisible();
  await expect(page.getByText("Accepted with a valid service token")).toBeVisible();
  await expect(page.getByText("Refused with status 401")).toBeVisible();
});

test("signing out ends the session", async ({ page }) => {
  await signIn(page, account("AUDITOR"));
  await page.getByRole("button", { name: "Sign out" }).first().click();
  await expect(page).toHaveURL(/\/sign-in/);
  await page.goto("/overview");
  await expect(page).toHaveURL(/\/sign-in/);
});
