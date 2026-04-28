import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/agiladmin.js";

test("admin reload keeps response in reload result container", async ({ page }) => {
  await loginAs(page, "admin");
  await page.goto("/reload");

  await expect(page.getByRole("heading", { name: "Reload budgets repository" })).toBeVisible();
  await page.getByRole("button", { name: "Reload", exact: true }).click();

  const result = page.locator("#reload-result");
  await expect(result).toBeVisible();
  await expect(result).toContainText(
    /(Reloaded successfully|Reload completed|not a git repository yet|Reload is unavailable)/,
  );
});

test("manager is denied reload page", async ({ page }) => {
  await loginAs(page, "manager");
  await page.goto("/reload");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
});
