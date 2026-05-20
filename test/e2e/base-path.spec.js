import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/agiladmin.js";

test("base-path mode renders prefixed URLs for login and upload", async ({ page }) => {
  const basePath = process.env.E2E_BASE_PATH || "/";
  test.skip(basePath === "/", "Set E2E_BASE_PATH to run base-path browser coverage.");

  await page.goto("/login");
  await expect(page.locator(`form[action="${basePath}/login"]`)).toBeVisible();
  await expect(page.locator(`script[src^="${basePath}/static/js/app.js?v="]`)).toHaveCount(1);
  await expect(page.locator(`link[href="${basePath}/static/css/app.css"]`)).toHaveCount(1);

  await loginAs(page, "admin");
  await expect(page.getByText("Logged in: admin")).toBeVisible();
  await expect(page.locator(`a[href="${basePath}/logout"]`).first()).toBeVisible();

  await page.goto("/timesheets");
  await expect(page.locator(`form[action="${basePath}/timesheets/upload"]`)).toBeVisible();
  await expect(page.locator(`#timesheet-workspace [hx-post="${basePath}/timesheets/upload"]`)).toHaveCount(1);
});
