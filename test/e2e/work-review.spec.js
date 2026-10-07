import { test, expect } from "@playwright/test";
import path from "node:path";
import fs from "node:fs/promises";
import { loginAs, readE2EState } from "./helpers/agiladmin.js";

const prefix = process.env.E2E_BASE_PATH === "/" ? "" : (process.env.E2E_BASE_PATH || "");
const route = (p) => `${prefix}${p}`;
const output = path.resolve("output/playwright/work-review");
const baseURL = process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:18080";

for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
  for (const scale of [100, 200]) {
    test(`review, overflow and stale at ${viewport.width}px / ${scale}%`, async ({ browser }) => {
      const context = await browser.newContext({ viewport, baseURL });
      const page = await context.newPage();
      await loginAs(page, "admin");
      const state = await readE2EState();
      await fs.mkdir(output, { recursive: true });
      const label = `${prefix ? "prefix-" : ""}${viewport.width}-${scale}`;
      for (const [surface, url, title] of [
        ["review", `/work/review/${state.work["review-id"]}`, "Review 2024-02 work"],
        ["overflow", `/work/month/${state.work["overflow-month"]}`, "Draft 2024-04"],
        ["stale", `/work/review/${state.work["stale-id"]}`, "Review 2024-03 work"],
      ]) {
        await page.goto(route(url));
        await page.evaluate((scale) => { document.documentElement.style.fontSize = `${scale}%`; }, scale);
        await expect(page.getByRole("heading", { name: title, exact: true })).toBeVisible();
        const widths = await page.evaluate(() => ({page: document.documentElement.scrollWidth, viewport: window.innerWidth,
          overflow: [...document.querySelectorAll("body *")].filter(el => el.getBoundingClientRect().right > window.innerWidth + 1)
            .slice(0, 8).map(el => ({tag: el.tagName, cls: el.className, width: el.getBoundingClientRect().width, text: el.textContent.slice(0, 60)}))}));
        expect(widths.page, JSON.stringify(widths)).toBeLessThanOrEqual(widths.viewport + 1);
        await page.screenshot({ path: path.join(output, `${surface}-${label}.png`), fullPage: true });
        if (surface === "review") {
          await expect(page.getByText("8.00 h", { exact: true }).first()).toBeVisible();
          await expect(page.getByText("2.00 h", { exact: true }).first()).toBeVisible();
          await expect(page.getByText("=literal <script>plain text</script> 😀", { exact: false }).first()).toBeAttached();
          const download = page.getByRole("link", { name: "Download reviewed workbook" });
          await download.focus();
          await page.keyboard.press("Tab");
          const confirm = page.getByRole("button", { name: "Confirm and publish month" });
          await expect(confirm).toBeFocused();
          await confirm.scrollIntoViewIfNeeded();
          await page.locator('section[aria-labelledby="work-confirmation"]').evaluate((el) => el.scrollIntoView({ block: "start" }));
          await page.evaluate(() => window.scrollBy(0, -document.querySelector("nav").getBoundingClientRect().height - 16));
          await page.screenshot({ path: path.join(output, `confirmation-${label}.png`) });
          expect(await confirm.evaluate((el) => el.getBoundingClientRect().width)).toBeLessThanOrEqual(viewport.width);
        } else {
          await expect(page.getByRole("button", { name: "Confirm and publish month" })).toHaveCount(0);
          await expect(page.getByRole("alert")).toBeVisible();
        }
      }
      await context.close();
    });
  }
}

test("owner isolation, browser download and no-JS confirmation fallback", async ({ browser }) => {
  const state = await readE2EState();
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 }, baseURL });
  const page = await context.newPage();
  async function signIn(username) {
    await page.goto(route("/login"));
    await page.locator('input[name="email"]').fill(username);
    await page.locator('input[name="password"]').fill(username);
    await page.locator('input[name="password"]').press("Enter");
    await expect(page.locator('a[href$="/logout"]').first()).toBeAttached();
  }
  await signIn("admin");
  await page.goto(route(`/work/review/${state.work["review-id"]}`));
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download reviewed workbook" }).press("Enter");
  expect((await download).suggestedFilename()).toBe("2024_timesheet_Admin.xlsx");
  await page.getByRole("button", { name: "Confirm and publish month" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Month publication" })).toBeVisible();
  await expect(page.getByText("Publication state: pushed")).toBeVisible();
  await page.goto(route(`/work/review/${state.work["review-id"]}`));
  await expect(page.getByText("Publication: pushed")).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirm and publish month" })).toHaveCount(0);
  await page.goto(route("/logout"));
  await signIn("manager");
  const denied = await page.goto(route(`/work/review/${state.work["review-id"]}`));
  expect(denied.status()).toBe(404);
  await expect(page.getByRole("button", { name: "Confirm and publish month" })).toHaveCount(0);
  await context.close();
});
