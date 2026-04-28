import { test, expect } from "@playwright/test";
import { loginAs, openTimesheetUpload, readE2EState, uploadTimesheet } from "./helpers/agiladmin.js";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

test("admin can login and upload a real timesheet", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "admin");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.admin);

  await expect(page.getByText("Uploaded: 2016_timesheet_Luca-Pacioli.xlsx")).toBeVisible();
  await page.getByRole("button", { name: "Contents" }).click();
  await expect(page.getByText("Contents of the new timesheet")).toBeVisible();
  await expect(page.getByText("Error parsing timesheet")).toHaveCount(0);
});

test("manager can login and upload their own timesheet", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "manager");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.manager);

  await expect(page.getByText("Uploaded: 2016_timesheet_Manager.xlsx")).toBeVisible();
  await page.getByRole("button", { name: "Contents" }).click();
  await expect(page.getByText("Contents of the new timesheet")).toBeVisible();
  await expect(page.getByText("Error parsing timesheet")).toHaveCount(0);
  await expect(page.getByText("Timesheet filename does not match the authenticated account")).toHaveCount(0);
  await expect(page.getByText("Timesheet owner in cell B3 does not match the authenticated account")).toHaveCount(0);
});

test("manager upload rejects another person's timesheet", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "manager");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.admin);

  await expect(page.getByText("Timesheet filename does not match the authenticated account")).toBeVisible();
});

test("upload error keeps workflow in timesheet workspace", async ({ page }) => {
  const invalidPath = path.join(os.tmpdir(), `agiladmin-e2e-invalid-${Date.now()}.txt`);
  await fs.writeFile(invalidPath, "not an xlsx", "utf8");

  await loginAs(page, "admin");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, invalidPath);

  await expect(page).toHaveURL(/\/timesheets$/);
  await expect(page.locator("#timesheet-workspace")).toBeVisible();
  await expect(page.getByText("Error parsing timesheet")).toBeVisible();
  await expect(page.locator('input[type="file"][name="file"]')).toBeVisible();
});
