import { test, expect } from "@playwright/test";
import { expectLoginForm, loginAs, logout } from "./helpers/agiladmin.js";

test("admin can login and logout", async ({ page }) => {
  await loginAs(page, "admin");
  await logout(page);
  await page.goto("/timesheets");
  await expectLoginForm(page);
});

test("manager can login and logout", async ({ page }) => {
  await loginAs(page, "manager");
  await logout(page);
  await page.goto("/timesheets");
  await expectLoginForm(page);
});

test("guest can login and logout", async ({ page }) => {
  await loginAs(page, "guest");
  await logout(page);
  await page.goto("/timesheets");
  await expectLoginForm(page);
});

test("invalid credentials fail without creating a session", async ({ page }) => {
  await page.goto("/login");
  await page.locator('input[name="email"]').fill("admin");
  await page.locator('input[name="password"]').fill("wrong-password");
  await page.locator('form[action="/login"] input[type="submit"]').click();

  await expect(page.getByText("Login failed:")).toBeVisible();
  await expect(page.getByRole("link", { name: "Logout" })).toHaveCount(0);

  await page.goto("/timesheets");
  await expectLoginForm(page);
});

test("guest restrictions after login", async ({ page }) => {
  await loginAs(page, "guest");

  await page.goto("/projects/list");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();

  await page.goto("/reload");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();

  await page.goto("/config");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();

  await page.goto("/persons/list");
  await expect(page.getByRole("link", { name: "Logout" })).toBeVisible();
  await expect(page.getByText("Personnel")).toHaveCount(0);
});
