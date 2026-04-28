import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./test/e2e",
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: "http://127.0.0.1:18080",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
});
