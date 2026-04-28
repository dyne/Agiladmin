import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/agiladmin.js";

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
  await expect(page.getByRole("heading", { name: "Persons", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Guest", exact: true })).toHaveCount(0);
});
