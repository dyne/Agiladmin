import { spawn } from "node:child_process";
import http from "node:http";
import { setTimeout as sleep } from "node:timers/promises";

const args = process.argv.slice(2);
const BACKEND_ORIGIN = "http://127.0.0.1:18080";
const PROXY_ORIGIN = "http://127.0.0.1:18081";
const E2E_BASE_PATH = normalizeBasePath(process.env.E2E_BASE_PATH ?? "/");
let server;
let proxyServer;

function normalizeBasePath(basePath) {
  const raw = String(basePath ?? "").trim();
  if (!raw || raw === "/") return "/";
  const cleaned = raw.replace(/^\/+/, "").replace(/\/+$/, "");
  return cleaned ? `/${cleaned}` : "/";
}

function withBasePath(pathname, basePath) {
  if (basePath === "/") return pathname;
  const route = pathname.startsWith("/") ? pathname : `/${pathname}`;
  return `${basePath}${route}`;
}

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

function startPrefixProxy(basePath) {
  const prefix = basePath;
  const server = http.createServer((req, res) => {
    try {
      const requestUrl = new URL(req.url ?? "/", PROXY_ORIGIN);
      const pathOnly = requestUrl.pathname;
      const hasPrefix = pathOnly === prefix || pathOnly.startsWith(`${prefix}/`);
      const strippedPath = hasPrefix
        ? pathOnly === prefix
          ? "/"
          : pathOnly.slice(prefix.length)
        : pathOnly;
      const targetPath = `${strippedPath}${requestUrl.search}`;
      const proxyReq = http.request(
        {
          protocol: "http:",
          hostname: "127.0.0.1",
          port: 18080,
          method: req.method,
          path: targetPath,
          headers: {
            ...req.headers,
            host: "127.0.0.1:18080",
          },
        },
        (proxyRes) => {
          res.writeHead(proxyRes.statusCode ?? 502, proxyRes.headers);
          proxyRes.pipe(res);
        },
      );
      proxyReq.on("error", (err) => {
        res.statusCode = 502;
        res.end(`proxy error: ${err.message}`);
      });
      req.pipe(proxyReq);
    } catch (err) {
      res.statusCode = 500;
      res.end(`proxy setup error: ${err.message}`);
    }
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(18081, "127.0.0.1", () => resolve(server));
  });
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

  if (E2E_BASE_PATH !== "/") {
    proxyServer = await startPrefixProxy(E2E_BASE_PATH);
  }

  const loginUrl = E2E_BASE_PATH === "/" ? `${BACKEND_ORIGIN}/login` : `${PROXY_ORIGIN}/login`;
  await waitForLogin(loginUrl);
  const baseURL = E2E_BASE_PATH === "/" ? BACKEND_ORIGIN : PROXY_ORIGIN;

  const runner = spawn("npx", ["playwright", "test", ...args], {
    stdio: "inherit",
    env: {
      ...process.env,
      PLAYWRIGHT_BASE_URL: baseURL,
    },
  });

  const testCode = await new Promise((resolve) => {
    runner.on("exit", (code) => resolve(code ?? 1));
  });

  if (server && !server.killed) {
    server.kill("SIGTERM");
  }
  if (proxyServer) {
    await new Promise((resolve) => proxyServer.close(resolve));
  }
  process.exit(testCode);
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    if (server && !server.killed) {
      server.kill("SIGTERM");
    }
    if (proxyServer) {
      proxyServer.close();
    }
    process.exit(130);
  });
}

main().catch((err) => {
  console.error(err);
  if (server && !server.killed) {
    server.kill("SIGTERM");
  }
  if (proxyServer) {
    proxyServer.close();
  }
  process.exit(1);
});
