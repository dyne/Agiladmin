import { test, expect } from "@playwright/test";
import { loginAs, openTimesheetUpload, readE2EState, uploadTimesheet } from "./helpers/agiladmin.js";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

async function sha256File(filePath) {
  const data = await fs.readFile(filePath);
  return createHash("sha256").update(data).digest("hex");
}

test("admin can login and upload a real timesheet", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "admin");
  await openTimesheetUpload(page);
  await expect(page.locator("[data-upload-progress]")).toBeVisible();
  await expect(page.locator("[data-upload-progress-label]")).toHaveText("0%");
  await uploadTimesheet(page, state.fixtures.admin);

  await expect(page.getByText("Uploaded: 2016_timesheet_Luca-Pacioli.xlsx")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Timesheet changes" })).toBeVisible();
  await expect(page.getByText("Differences: old (to the left) and new (to the right)")).toHaveCount(0);
  const uploadedTempPath = await page.locator('input[name="tempfile"]').inputValue();
  await expect(uploadedTempPath).toBeTruthy();
  await expect(sha256File(uploadedTempPath)).resolves.toBe(await sha256File(state.fixtures.admin));
  await page.getByRole("button", { name: "Contents" }).click();
  await expect(page.getByText("Contents of the new timesheet")).toBeVisible();
  await expect(page.getByText("Error parsing timesheet")).toHaveCount(0);
});

test("upload uses local progress without the page-loading overlay", async ({ page }) => {
  const state = await readE2EState();
  let releaseUpload;
  const uploadCanFinish = new Promise((resolve) => {
    releaseUpload = resolve;
  });

  await page.route("**/timesheets/upload", async (route) => {
    await uploadCanFinish;
    await route.fulfill({
      status: 200,
      contentType: "text/html; charset=utf-8",
      body: '<div id="timesheet-workspace">Upload intercepted</div>',
    });
  });

  await loginAs(page, "admin");
  await openTimesheetUpload(page);
  await page.locator('input[type="file"][name="file"]').setInputFiles(state.fixtures.admin);
  await page.locator("#field-submit").click();

  await expect(page.locator("[data-upload-progress-label]")).toHaveText("Uploading...");
  await expect(page.locator('[data-page-loading="true"]')).toBeHidden();

  releaseUpload();
  await expect(page.getByText("Upload intercepted")).toBeVisible();
});

test("manager can login and upload their own timesheet", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "manager");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.manager);

  await expect(page.getByText("Uploaded: 2016_timesheet_Manager.xlsx")).toBeVisible();
  const uploadedTempPath = await page.locator('input[name="tempfile"]').inputValue();
  await expect(uploadedTempPath).toBeTruthy();
  await expect(sha256File(uploadedTempPath)).resolves.toBe(await sha256File(state.fixtures.manager));
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

test("cancel after upload returns to upload form", async ({ page }) => {
  const state = await readE2EState();
  await loginAs(page, "admin");
  await openTimesheetUpload(page);
  await uploadTimesheet(page, state.fixtures.admin);

  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByText("Canceled upload of timesheet:")).toBeVisible();
  await expect(page.locator('input[type="file"][name="file"]')).toBeVisible();
});
