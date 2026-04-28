import { spawn } from "node:child_process";
import { promises as fs } from "node:fs";
import { createWriteStream } from "node:fs";
import path from "node:path";
import os from "node:os";

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
const TMP_ROOT_PREFIX = "agiladmin-e2e-";
const STATE_DIR = path.join(REPO_ROOT, ".tmp");
const STATE_PATH = path.join(STATE_DIR, "agiladmin-e2e-state.json");
const OUTPUT_DIR = path.join(REPO_ROOT, "output", "playwright");
const LOG_PATH = path.join(OUTPUT_DIR, "agiladmin-server.log");

function yamlConfig(budgetsPath, sshKeyPath) {
  return [
    "appname: agiladmin-e2e",
    "paths: []",
    "filename: agiladmin-e2e.yaml",
    "agiladmin:",
    "  budgets:",
    "    git: ssh://example.invalid/dyne/budgets",
    `    path: ${JSON.stringify(`${budgetsPath}/`)}`,
    `    ssh-key: ${JSON.stringify(sshKeyPath)}`,
    "  source:",
    "    git: https://github.com/dyne/agiladmin",
    "    update: true",
    "  webserver:",
    "    host: 127.0.0.1",
    "    port: 18080",
    "    anti-forgery: false",
  ].join("\n");
}

async function copyBudgetFixtures(targetDir) {
  const srcDir = path.join(REPO_ROOT, "test", "assets");
  const entries = await fs.readdir(srcDir, { withFileTypes: true });
  const whitelist = new Set(["UNO.yaml", "DUE.yaml", "TRE.yaml", "BADFIELDS.yaml", "BROKEN.yaml", "INVALIDYAML.yaml"]);
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".yaml")) continue;
    if (!whitelist.has(entry.name)) continue;
    await fs.copyFile(path.join(srcDir, entry.name), path.join(targetDir, entry.name));
  }
}

async function generateOwnedFixture(sourceFixturePath, targetFixturePath, ownerName) {
  const expr = [
    "(load-file \"scripts/e2e/generate-manager-fixture.clj\")",
    `(agiladmin.e2e.generate-manager-fixture/-main ${JSON.stringify(sourceFixturePath)} ${JSON.stringify(targetFixturePath)} ${JSON.stringify(ownerName)})`,
  ].join(" ");
  await new Promise((resolve, reject) => {
    const child = spawn("clojure", ["-M", "-e", expr], {
      cwd: REPO_ROOT,
      stdio: "inherit",
    });
    child.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`fixture generation failed: ${code}`))));
    child.on("error", reject);
  });
}

async function prepareEnv() {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), TMP_ROOT_PREFIX));
  const budgetsDir = path.join(tempRoot, "budgets");
  const fixturesDir = path.join(tempRoot, "fixtures");
  await fs.mkdir(budgetsDir, { recursive: true });
  await fs.mkdir(fixturesDir, { recursive: true });
  await fs.mkdir(STATE_DIR, { recursive: true });
  await fs.mkdir(OUTPUT_DIR, { recursive: true });

  await copyBudgetFixtures(budgetsDir);

  const sshKeyPath = path.join(tempRoot, "id_rsa");
  await fs.copyFile(path.join(REPO_ROOT, "id_rsa"), sshKeyPath);
  await fs.copyFile(path.join(REPO_ROOT, "id_rsa.pub"), `${sshKeyPath}.pub`);

  const adminFixturePath = path.join(fixturesDir, "2016_timesheet_Luca-Pacioli.xlsx");
  const managerFixturePath = path.join(fixturesDir, "2016_timesheet_Manager.xlsx");
  const guestFixturePath = path.join(fixturesDir, "2016_timesheet_Guest.xlsx");
  await fs.copyFile(path.join(REPO_ROOT, "test", "assets", "2016_timesheet_Luca-Pacioli.xlsx"), adminFixturePath);
  await generateOwnedFixture(adminFixturePath, managerFixturePath, "Manager");
  await generateOwnedFixture(adminFixturePath, guestFixturePath, "Guest");

  await fs.copyFile(managerFixturePath, path.join(budgetsDir, "2026_timesheet_Manager.xlsx"));
  await fs.copyFile(guestFixturePath, path.join(budgetsDir, "2026_timesheet_Guest.xlsx"));

  const configPath = path.join(tempRoot, "agiladmin-e2e.yaml");
  await fs.writeFile(configPath, `${yamlConfig(budgetsDir, sshKeyPath)}\n`, "utf8");

  const state = {
    tempRoot,
    configPath,
    budgetsDir,
    fixtures: {
      admin: adminFixturePath,
      manager: managerFixturePath,
      guest: guestFixturePath,
    },
    logPath: LOG_PATH,
  };
  await fs.writeFile(STATE_PATH, JSON.stringify(state, null, 2), "utf8");
  return state;
}

async function start() {
  const state = await prepareEnv();
  const logStream = createWriteStream(LOG_PATH, { flags: "a" });
  const child = spawn("clojure", ["-M:run"], {
    cwd: REPO_ROOT,
    env: {
      ...process.env,
      AGILADMIN_DEV_AUTH: "1",
      AGILADMIN_CONF: state.configPath,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  child.stdout.pipe(logStream);
  child.stderr.pipe(logStream);
  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);

  const shutdown = async (signal) => {
    if (!child.killed) {
      child.kill("SIGTERM");
    }
    logStream.end();
    try {
      await fs.rm(state.tempRoot, { recursive: true, force: true });
    } catch (_) {}
    process.exit(signal === "exit" ? 0 : 130);
  };

  for (const sig of ["SIGINT", "SIGTERM"]) {
    process.on(sig, () => {
      shutdown(sig).catch((err) => {
        console.error(err);
        process.exit(1);
      });
    });
  }
  process.on("exit", () => {
    if (!child.killed) child.kill("SIGTERM");
  });

  child.on("exit", async (code) => {
    logStream.end();
    try {
      await fs.rm(state.tempRoot, { recursive: true, force: true });
    } catch (_) {}
    process.exit(code ?? 1);
  });
}

start().catch((err) => {
  console.error(err);
  process.exit(1);
});
