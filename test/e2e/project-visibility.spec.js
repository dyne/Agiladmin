import { test, expect } from "@playwright/test";
import { loginAs } from "./helpers/agiladmin.js";

async function openFirstProjectFromList(page) {
  await page.goto("/projects/list");
  const candidates = ["DUE", "TRE", "UNO"];
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
  await expect(page.locator("#project-details .tabs")).toBeVisible();
}

test("admin project view shows edit controls and cost data", async ({ page }) => {
  await loginAs(page, "admin");
  await openFirstProjectFromList(page);

  await expect(page.getByText("Edit project configuration")).toBeVisible();
  await expect(page.locator("th", { hasText: "Cost" }).first()).toBeVisible();
});

test("manager project view hides edit controls and cost data", async ({ page }) => {
  await loginAs(page, "manager");
  await openFirstProjectFromList(page);

  await expect(page.getByText("Edit project configuration")).toHaveCount(0);
  await expect(page.locator("th", { hasText: "Cost" })).toHaveCount(0);
});

test("manager direct project edit route is unauthorized", async ({ page }) => {
  await loginAs(page, "manager");
  const response = await page.request.post("/projects/edit", {
    form: {
      project: "DUE",
    },
  });
  await expect(response.ok()).toBeTruthy();
  const body = await response.text();
  expect(body).toContain("Unauthorized access.");
});
