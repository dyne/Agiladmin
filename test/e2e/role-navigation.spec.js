import { test, expect } from "@playwright/test";
import { expectLoginForm, loginAs } from "./helpers/agiladmin.js";

test("admin navbar shows admin and project entries", async ({ page }) => {
  await loginAs(page, "admin");
  await page.goto("/persons/list");

  await expect(page.getByRole("link", { name: "Personnel" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Projects" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Reload" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Configuration" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Logout" })).toBeVisible();
});

test("manager navbar hides admin-only entries", async ({ page }) => {
  await loginAs(page, "manager");
  await page.goto("/persons/list");

  await expect(page.getByRole("link", { name: "Projects" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Personnel" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Reload" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Configuration" })).toHaveCount(0);
});

test("guest navbar hides project and admin entries", async ({ page }) => {
  await loginAs(page, "guest");
  await page.goto("/persons/list");

  await expect(page.getByRole("link", { name: "Projects" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Reload" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Configuration" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Logout" })).toBeVisible();
});

test("guarded admin routes enforce role restrictions", async ({ page, context }) => {
  await page.goto("/reload");
  await expectLoginForm(page);

  await context.clearCookies();
  await loginAs(page, "manager");
  await page.goto("/reload");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
  await page.goto("/config");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
  await page.goto("/config/edit");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();

  await context.clearCookies();
  await loginAs(page, "guest");
  await page.goto("/reload");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
  await page.goto("/config");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
  await page.goto("/config/edit");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();

  await context.clearCookies();
  await loginAs(page, "admin");
  await page.goto("/reload");
  await expect(page.getByText("Reload budgets repository")).toBeVisible();
  await page.goto("/config");
  await expect(page.getByText("SSH authentication keys")).toBeVisible();
  await page.goto("/config/edit");
  await expect(page.getByText("Configuration editor")).toBeVisible();
});
