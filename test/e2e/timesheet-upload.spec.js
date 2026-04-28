import { test, expect } from "@playwright/test";
import { login, openTimesheetUpload, readE2EState, uploadTimesheet } from "./helpers/agiladmin.js";

test("admin can login and upload a real timesheet", async ({ page }) => {
  const state = await readE2EState();
  await login(page, "admin", "admin");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.admin);

  await expect(page.getByText("Uploaded: 2016_timesheet_Luca-Pacioli.xlsx")).toBeVisible();
  await page.getByRole("button", { name: "Contents" }).click();
  await expect(page.getByText("Contents of the new timesheet")).toBeVisible();
  await expect(page.getByText("Error parsing timesheet")).toHaveCount(0);
});

test("manager can login and upload their own timesheet", async ({ page }) => {
  const state = await readE2EState();
  await login(page, "manager", "manager");
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
  await login(page, "manager", "manager");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.admin);

  await expect(page.getByText("Timesheet filename does not match the authenticated account")).toBeVisible();
});
