import { spawn } from "node:child_process";

const child = spawn(
  process.execPath,
  ["./scripts/e2e/run-playwright.mjs", "test/e2e/base-path.spec.js", "test/e2e/work-review.spec.js"],
  {
    stdio: "inherit",
    env: {...process.env, E2E_BASE_PATH: "/agiladmin"},
  },
);

child.on("exit", (code) => process.exit(code ?? 1));
child.on("error", (error) => {
  console.error(error);
  process.exit(1);
});
