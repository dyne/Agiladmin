import { expect } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";

const STATE_PATH = path.resolve(process.cwd(), ".tmp/agiladmin-e2e-state.json");
export const USERS = {
  admin: { username: "admin", password: "admin" },
  manager: { username: "manager", password: "manager" },
  guest: { username: "guest", password: "guest" },
};

export async function readE2EState() {
  const raw = await fs.readFile(STATE_PATH, "utf8");
  const state = JSON.parse(raw);
  for (const fixturePath of Object.values(state.fixtures || {})) {
    await fs.access(fixturePath);
  }
  return state;
}

export async function login(page, username, password) {
  await page.goto("/login");
  await page.locator('input[name="email"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('form[action="/login"] input[type="submit"]').click();
}

export async function expectLoggedInAs(page, username) {
  await expect(page.getByText(`Logged in: ${username}`)).toBeVisible();
}

export async function expectLoginForm(page) {
  await expect(page.locator('form[action="/login"]')).toBeVisible();
  await expect(page.locator('input[name="email"]')).toBeVisible();
  await expect(page.locator('input[name="password"]')).toBeVisible();
}

export async function loginAs(page, role) {
  const user = USERS[role];
  if (!user) {
    throw new Error(`Unknown role: ${role}`);
  }
  await login(page, user.username, user.password);
  if (role === "guest") {
    await expect(page).toHaveURL(/\/persons\/list$/);
    await expect(page.getByRole("link", { name: "Logout" })).toBeVisible();
    await expect(page.locator('form[action="/login"]')).toHaveCount(0);
    return;
  }
  await expectLoggedInAs(page, user.username);
}

export async function logout(page) {
  await page.goto("/logout");
  await expect(page.getByText("Logged out.")).toBeVisible();
}

export async function openTimesheetUpload(page) {
  await page.goto("/timesheets");
  await expect(page.locator("#timesheet-workspace")).toBeVisible();
  await expect(page.locator('input[type="file"][name="file"]')).toBeVisible();
}

export async function uploadTimesheet(page, fixturePath) {
  await page.locator('input[type="file"][name="file"]').setInputFiles(fixturePath);
  await page.locator("#field-submit").click();
  await expect(page.locator("#timesheet-workspace")).toBeVisible();
}
