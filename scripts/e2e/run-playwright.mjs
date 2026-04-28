import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const args = process.argv.slice(2);
let server;

async function waitForLogin(url, timeoutMs = 90000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const res = await fetch(url, { redirect: "manual" });
      if (res.status >= 200 && res.status < 500) {
        return;
      }
    } catch (_) {}
    await sleep(1000);
  }
  throw new Error(`Timed out waiting for ${url}`);
}

async function main() {
  server = spawn("node", ["./scripts/e2e/start-agiladmin.mjs"], {
    stdio: "inherit",
    env: process.env,
  });

  server.on("exit", (code) => {
    if (code !== 0) {
      console.error(`E2E server exited early with code ${code}`);
    }
  });

  await waitForLogin("http://127.0.0.1:18080/login");

  const runner = spawn("npx", ["playwright", "test", ...args], {
    stdio: "inherit",
    env: process.env,
  });

  const testCode = await new Promise((resolve) => {
    runner.on("exit", (code) => resolve(code ?? 1));
  });

  if (server && !server.killed) {
    server.kill("SIGTERM");
  }
  process.exit(testCode);
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    if (server && !server.killed) {
      server.kill("SIGTERM");
    }
    process.exit(130);
  });
}

main().catch((err) => {
  console.error(err);
  if (server && !server.killed) {
    server.kill("SIGTERM");
  }
  process.exit(1);
});
