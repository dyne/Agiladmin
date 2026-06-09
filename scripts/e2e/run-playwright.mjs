import { spawn, spawnSync } from "node:child_process";
import { promises as fs } from "node:fs";
import http from "node:http";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const BACKEND_ORIGIN = "http://127.0.0.1:18080";
const PROXY_ORIGIN = "http://127.0.0.1:18081";
const E2E_BASE_PATH = normalizeBasePath(process.env.E2E_BASE_PATH ?? "/");
const E2E_PROXY = process.env.E2E_PROXY ?? "node";
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
let server;
let proxyServer;
let caddyProcess;

function stopProcessTree(child) {
  if (!child || child.killed) return;
  if (process.platform === "win32") {
    spawnSync("taskkill.exe", ["/pid", String(child.pid), "/T", "/F"], {
      stdio: "ignore",
    });
    return;
  }
  child.kill("SIGTERM");
}

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

function caddyConfig(basePath) {
  if (basePath === "/") {
    return [
      "{",
      "\tadmin off",
      "}",
      "",
      "http://127.0.0.1:18081 {",
      "\treverse_proxy 127.0.0.1:18080",
      "}",
      "",
    ].join("\n");
  }

  return [
    "{",
    "\tadmin off",
    "}",
    "",
    "http://127.0.0.1:18081 {",
    `\thandle_path ${basePath}/* {`,
    "\t\treverse_proxy 127.0.0.1:18080",
    "\t}",
    "\treverse_proxy 127.0.0.1:18080",
    "}",
    "",
  ].join("\n");
}

async function startCaddyProxy(basePath) {
  const tmpDir = path.join(REPO_ROOT, ".tmp");
  const configPath = path.join(tmpDir, "agiladmin-e2e.Caddyfile");
  await fs.mkdir(tmpDir, { recursive: true });
  await fs.writeFile(configPath, caddyConfig(basePath), "utf8");

  const child = spawn("caddy", ["run", "--config", configPath, "--adapter", "caddyfile"], {
    cwd: REPO_ROOT,
    stdio: "inherit",
  });
  caddyProcess = child;
  child.on("exit", (code) => {
    if (code !== 0 && code !== null) {
      console.error(`Caddy proxy exited early with code ${code}`);
    }
  });

  return child;
}

function stopCaddyProxy() {
  if (caddyProcess && !caddyProcess.killed) {
    caddyProcess.kill("SIGTERM");
  }
}

async function main() {
  const localTemp = path.join(REPO_ROOT, ".tmp");
  await fs.mkdir(localTemp, { recursive: true });
  const childEnv = {...process.env, TEMP: localTemp, TMP: localTemp};
  server = spawn("node", ["./scripts/e2e/start-agiladmin.mjs"], {
    stdio: "inherit",
    env: childEnv,
  });

  server.on("exit", (code) => {
    if (code !== 0) {
      console.error(`E2E server exited early with code ${code}`);
    }
  });

  if (E2E_PROXY === "caddy") {
    await startCaddyProxy(E2E_BASE_PATH);
  } else if (E2E_BASE_PATH !== "/") {
    proxyServer = await startPrefixProxy(E2E_BASE_PATH);
  }

  const proxied = E2E_PROXY === "caddy" || E2E_BASE_PATH !== "/";
  const loginUrl = proxied
    ? `${PROXY_ORIGIN}${E2E_BASE_PATH === "/" ? "/login" : `${E2E_BASE_PATH}/login`}`
    : `${BACKEND_ORIGIN}/login`;
  await waitForLogin(loginUrl);
  const baseURL = proxied ? PROXY_ORIGIN : BACKEND_ORIGIN;

  const playwrightCli = path.join(REPO_ROOT, "node_modules", "@playwright", "test", "cli.js");
  const runner = spawn(process.execPath, [playwrightCli, "test", ...args], {
    stdio: "inherit",
    env: {
      ...childEnv,
      PLAYWRIGHT_BASE_URL: baseURL,
    },
  });

  const testCode = await new Promise((resolve) => {
    runner.on("exit", (code) => resolve(code ?? 1));
  });

  stopProcessTree(server);
  if (proxyServer) {
    await new Promise((resolve) => proxyServer.close(resolve));
  }
  stopCaddyProxy();
  process.exit(testCode);
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    stopProcessTree(server);
    if (proxyServer) {
      proxyServer.close();
    }
    stopCaddyProxy();
    process.exit(130);
  });
}

main().catch((err) => {
  console.error(err);
  stopProcessTree(server);
  if (proxyServer) {
    proxyServer.close();
  }
  stopCaddyProxy();
  process.exit(1);
});
