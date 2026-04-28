import { expect } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";

const STATE_PATH = path.resolve(process.cwd(), ".tmp/agiladmin-e2e-state.json");

export async function readE2EState() {
  const raw = await fs.readFile(STATE_PATH, "utf8");
  return JSON.parse(raw);
}

export async function login(page, username, password) {
  await page.goto("/login");
  await page.locator('input[name="email"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('form[action="/login"] input[type="submit"]').click();
  await expect(page.getByText(`Logged in: ${username}`)).toBeVisible();
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
