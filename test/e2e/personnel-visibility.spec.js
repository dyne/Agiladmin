import { test, expect } from "@playwright/test";
import { loginAs, readE2EState } from "./helpers/agiladmin.js";

test("admin sees personnel list including multiple people", async ({ page }) => {
  await loginAs(page, "admin");
  await page.goto("/persons/list");

  await expect(page.getByRole("heading", { name: "Persons", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Manager", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Guest", exact: true })).toBeVisible();
});

test("manager sees own person page instead of the personnel list", async ({ page }) => {
  await loginAs(page, "manager");
  await page.goto("/persons/list");

  await expect(page.getByRole("heading", { name: /Manager$/ })).toBeVisible();
  await expect(page.locator("#timesheet-workspace")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Persons", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Guest", exact: true })).toHaveCount(0);
});

test("manager can upload from their person page", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "manager");
  await page.goto("/persons/list");

  await page.locator('input[type="file"][name="file"]').setInputFiles(state.fixtures.manager);
  await page.locator("#field-submit").click();

  await expect(page.getByText("Uploaded: 2016_timesheet_Manager.xlsx")).toBeVisible();
});

test("timesheet download does not leave the page-loading overlay visible", async ({ page }) => {
  await loginAs(page, "manager");
  await page.goto("/persons/list");

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download current timesheet" }).click();
  const download = await downloadPromise;

  expect(download.suggestedFilename()).toBe("2026_timesheet_Manager.xlsx");
  await expect(page.locator('[data-page-loading="true"]')).toBeHidden();
});
