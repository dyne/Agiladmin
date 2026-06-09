import { test, expect } from "@playwright/test";
import { expectLoginForm, loginAs } from "./helpers/agiladmin.js";

async function openFirstProjectFromList(page, candidates = ["DUE", "TRE", "UNO"]) {
  let clicked = false;

  for (const project of candidates) {
    const button = page.getByRole("button", { name: project, exact: true });
    if ((await button.count()) > 0) {
      await button.first().click();
      clicked = true;
      break;
    }
  }

  expect(clicked).toBeTruthy();
  await expect(page.locator("#project-details .tabs")).toBeVisible({ timeout: 15000 });
}

test("admin can access project list and open a project", async ({ page }) => {
  await loginAs(page, "admin");
  await page.goto("/projects/list");
  await openFirstProjectFromList(page);
});

test("manager can access project list and open a project", async ({ page }) => {
  const consoleErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  await loginAs(page, "manager");
  await page.goto("/projects/list");
  await openFirstProjectFromList(page, ["UNO"]);

  expect(await page.evaluate(() => typeof window.Plotly)).toBe("object");
  await expect(page.getByText(/cost/i)).toHaveCount(0);

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator("#project-details .tabs")).toBeVisible();
  expect(consoleErrors).toEqual([]);
});

test("guest is denied project list", async ({ page }) => {
  await loginAs(page, "guest");
  await page.goto("/projects/list");
  await expect(page.getByText("Unauthorized access.")).toBeVisible();
});

test("logged out user is redirected to login for project list", async ({ page }) => {
  await page.goto("/projects/list");
  await expectLoginForm(page);
});
