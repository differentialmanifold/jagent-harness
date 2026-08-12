#!/usr/bin/env node

import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  accessSync,
  constants as fsConstants,
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync
} from "node:fs";
import { createServer } from "node:net";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseEnv } from "node:util";

const IS_WINDOWS = process.platform === "win32";
const LAUNCHER_DIR = dirname(fileURLToPath(import.meta.url));
const APP_DIR = resolve(LAUNCHER_DIR, "..");
const ROOT_DIR = resolve(APP_DIR, "..", "..");
const FRONTEND_DIR = join(ROOT_DIR, "frontend");
const BACKEND_TARGET_DIR = join(APP_DIR, "target");
const LOCAL_ENV_FILE = join(LAUNCHER_DIR, ".env.coding.local");
const FRONTEND_PORT = 5173;

const JAVA_COMMAND = IS_WINDOWS ? "java.exe" : "java";
const JAVAC_COMMAND = IS_WINDOWS ? "javac.exe" : "javac";
const MAVEN_COMMAND = IS_WINDOWS ? "mvn.cmd" : "mvn";
const NPM_COMMAND = IS_WINDOWS ? "npm.cmd" : "npm";
const TRACKED_PROCESSES = new Set();

let shutdownPromise;

function parsePort(value) {
  if (!/^\d+$/.test(value)) {
    throw new Error("SERVER_PORT must be a number between 1 and 65535.");
  }

  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("SERVER_PORT must be a number between 1 and 65535.");
  }
  if (port === FRONTEND_PORT) {
    throw new Error(`SERVER_PORT cannot use the frontend port ${FRONTEND_PORT}.`);
  }
  return port;
}

function validateNodeVersion() {
  const [major, minor] = process.versions.node.split(".").map(Number);
  const supported =
    (major === 20 && minor >= 19) ||
    (major === 22 && minor >= 12) ||
    major > 22;

  if (!supported) {
    throw new Error("Node.js ^20.19.0 or >=22.12.0 is required by Vite.");
  }
}

function loadLocalEnvironment() {
  if (!existsSync(LOCAL_ENV_FILE)) {
    return false;
  }

  let values;
  try {
    const contents = readFileSync(LOCAL_ENV_FILE, "utf8").replace(/^\uFEFF/, "");
    values = parseEnv(contents);
  } catch (error) {
    throw new Error(
      `Could not parse ${LOCAL_ENV_FILE}: ${
        error instanceof Error ? error.message : error
      }`
    );
  }

  for (const [key, value] of Object.entries(values)) {
    if (process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
  return true;
}

function validateHttpUrl(value, name) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${name} must be a valid HTTP or HTTPS URL.`);
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(`${name} must be a valid HTTP or HTTPS URL.`);
  }
}

function commandInvocation(command, args) {
  if (!IS_WINDOWS || !command.toLowerCase().endsWith(".cmd")) {
    return { command, args };
  }

  // These tokens are launcher-owned constants. Keeping paths in cwd avoids
  // cmd.exe quoting issues for repositories whose names contain spaces.
  const commandLine = [command, ...args].join(" ");
  return {
    command: process.env.ComSpec || "cmd.exe",
    args: ["/d", "/s", "/c", commandLine]
  };
}

function waitForExit(child) {
  return new Promise((resolve) => {
    let settled = false;

    child.once("error", (error) => {
      if (!settled) {
        settled = true;
        resolve({ code: null, signal: null, error });
      }
    });
    child.once("exit", (code, signal) => {
      if (!settled) {
        settled = true;
        resolve({ code, signal, error: null });
      }
    });
  });
}

function spawnTracked(command, args, options = {}) {
  const invocation = commandInvocation(command, args);
  const child = spawn(invocation.command, invocation.args, {
    cwd: options.cwd || ROOT_DIR,
    env: options.env || process.env,
    stdio: options.stdio || "inherit",
    detached: !IS_WINDOWS,
    windowsHide: false
  });
  const record = {
    child,
    label: options.label || command,
    exit: waitForExit(child)
  };
  TRACKED_PROCESSES.add(record);
  return record;
}

async function runCommand(command, args, options = {}) {
  const record = spawnTracked(command, args, options);
  try {
    return await record.exit;
  } finally {
    TRACKED_PROCESSES.delete(record);
  }
}

async function requireCommand(command, args, message) {
  let outcome;
  try {
    outcome = await runCommand(command, args, {
      label: command,
      stdio: "ignore"
    });
  } catch {
    throw new Error(message);
  }

  if (outcome.error || outcome.code !== 0) {
    throw new Error(message);
  }
}

function isRunning(record) {
  return (
    record.child.pid &&
    record.child.exitCode === null &&
    record.child.signalCode === null
  );
}

function killPosixProcessGroup(record, signal) {
  if (!record.child.pid) {
    return;
  }

  try {
    process.kill(-record.child.pid, signal);
  } catch (error) {
    if (error.code !== "ESRCH") {
      try {
        record.child.kill(signal);
      } catch {
        // The process exited while cleanup was running.
      }
    }
  }
}

function killWindowsProcessTree(record) {
  if (!record.child.pid) {
    return;
  }

  const result = spawnSync(
    "taskkill.exe",
    ["/PID", String(record.child.pid), "/T", "/F"],
    { stdio: "ignore", windowsHide: true }
  );
  if ((result.error || result.status !== 0) && isRunning(record)) {
    try {
      if (!record.child.kill()) {
        console.warn(`Warning: could not stop ${record.label}.`);
      }
    } catch {
      console.warn(`Warning: could not stop ${record.label}.`);
    }
  }
}

function forceStopTrackedProcesses() {
  for (const record of TRACKED_PROCESSES) {
    if (!isRunning(record)) {
      continue;
    }
    if (IS_WINDOWS) {
      killWindowsProcessTree(record);
    } else {
      killPosixProcessGroup(record, "SIGKILL");
    }
  }
  TRACKED_PROCESSES.clear();
}

async function stopTrackedProcesses() {
  const running = [...TRACKED_PROCESSES].filter(isRunning);

  if (IS_WINDOWS) {
    for (const record of running) {
      killWindowsProcessTree(record);
    }
    TRACKED_PROCESSES.clear();
    return;
  }

  for (const record of running) {
    killPosixProcessGroup(record, "SIGTERM");
  }
  if (running.length > 0) {
    await delay(1500);
  }
  for (const record of running) {
    if (isRunning(record)) {
      killPosixProcessGroup(record, "SIGKILL");
    }
  }
  TRACKED_PROCESSES.clear();
}

function shutdown(exitCode) {
  if (shutdownPromise) {
    forceStopTrackedProcesses();
    process.exit(exitCode);
  }

  shutdownPromise = (async () => {
    await stopTrackedProcesses();
    process.exit(exitCode);
  })();
  return shutdownPromise;
}

function registerSignalHandlers() {
  process.on("SIGINT", () => {
    void shutdown(130);
  });
  process.on("SIGTERM", () => {
    void shutdown(143);
  });

  if (IS_WINDOWS) {
    process.on("SIGBREAK", () => {
      void shutdown(130);
    });
  } else {
    process.on("SIGHUP", () => {
      void shutdown(129);
    });
  }

  process.on("exit", () => {
    if (!shutdownPromise && TRACKED_PROCESSES.size > 0) {
      forceStopTrackedProcesses();
    }
  });
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function assertPortAvailable(port) {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once("error", () => {
      reject(
        new Error(
          `Port ${port} is already in use. Stop the existing process and try again.`
        )
      );
    });
    server.listen({ host: "127.0.0.1", port, exclusive: true }, () => {
      server.close((error) => {
        if (error) {
          reject(error);
        } else {
          resolve();
        }
      });
    });
  });
}

async function assertLauncherPortsAvailable(backendPort) {
  await Promise.all([
    assertPortAvailable(backendPort),
    assertPortAvailable(FRONTEND_PORT)
  ]);
}

function calculateDependencyHash() {
  const hash = createHash("sha256");
  for (const file of ["package.json", "package-lock.json"]) {
    hash.update(file);
    hash.update(readFileSync(join(FRONTEND_DIR, file)));
  }
  return hash.digest("hex");
}

function canAccess(file, mode) {
  try {
    accessSync(file, mode);
    return true;
  } catch {
    return false;
  }
}

async function ensureFrontendDependencies() {
  const viteEntry = join(
    FRONTEND_DIR,
    "node_modules",
    "vite",
    "bin",
    "vite.js"
  );
  const dependencyMarker = join(
    FRONTEND_DIR,
    "node_modules",
    ".jagent-deps.sha256"
  );
  const dependencyHash = calculateDependencyHash();
  const installedHash = existsSync(dependencyMarker)
    ? readFileSync(dependencyMarker, "utf8").trim()
    : "";
  const accessMode = IS_WINDOWS ? fsConstants.F_OK : fsConstants.R_OK;
  const needsInstall =
    !canAccess(viteEntry, accessMode) || dependencyHash !== installedHash;

  if (needsInstall) {
    console.log("Installing frontend dependencies with npm ci...");
    const outcome = await runCommand(NPM_COMMAND, ["ci"], {
      cwd: FRONTEND_DIR,
      label: "npm ci"
    });
    if (outcome.error || outcome.code !== 0) {
      throw new Error("Frontend dependency installation failed.");
    }
    if (!canAccess(viteEntry, accessMode)) {
      throw new Error("Vite was not installed by npm ci.");
    }
    writeFileSync(dependencyMarker, `${dependencyHash}\n`, "ascii");
  } else {
    console.log("Frontend dependencies are up to date.");
  }

  return viteEntry;
}

async function buildBackend() {
  console.log("\nBuilding the coding backend...");
  const outcome = await runCommand(
    MAVEN_COMMAND,
    [
      "-f",
      "pom.xml",
      "-pl",
      "examples/coding-tool-app",
      "-am",
      "package",
      "-DskipTests"
    ],
    {
      cwd: ROOT_DIR,
      label: "Maven build"
    }
  );
  if (outcome.error || outcome.code !== 0) {
    throw new Error("Backend build failed.");
  }
}

function findBackendJar() {
  const candidates = readdirSync(BACKEND_TARGET_DIR)
    .filter(
      (name) =>
        name.startsWith("coding-tool-app-") &&
        name.endsWith(".jar") &&
        !/(?:-sources|-javadoc|-tests)\.jar$/.test(name)
    )
    .map((name) => {
      const path = join(BACKEND_TARGET_DIR, name);
      return { path, modified: statSync(path).mtimeMs };
    })
    .sort((left, right) => right.modified - left.modified);

  if (candidates.length === 0) {
    throw new Error("The coding backend JAR was not produced.");
  }
  return candidates[0].path;
}

async function isHttpReady(url) {
  try {
    const response = await fetch(url, {
      cache: "no-store",
      signal: AbortSignal.timeout(1000)
    });
    const ready = response.ok;
    await response.arrayBuffer();
    return ready;
  } catch {
    return false;
  }
}

async function waitForReadiness(frontendUrl, backendHealthUrl) {
  const deadline = Date.now() + 90_000;

  while (Date.now() < deadline) {
    const [frontendReady, backendReady] = await Promise.all([
      isHttpReady(frontendUrl),
      isHttpReady(backendHealthUrl)
    ]);
    if (frontendReady && backendReady) {
      return { type: "ready" };
    }
    await delay(Math.min(1000, Math.max(0, deadline - Date.now())));
  }

  return { type: "timeout" };
}

function openBrowser(url) {
  let command;
  let args;

  if (IS_WINDOWS) {
    command = "explorer.exe";
    args = [url];
  } else if (process.platform === "darwin") {
    command = "open";
    args = [url];
  } else {
    command = "xdg-open";
    args = [url];
  }

  try {
    const browser = spawn(command, args, {
      detached: true,
      stdio: "ignore",
      windowsHide: true
    });
    browser.once("error", () => {
      console.warn(`Warning: could not open the browser. Open ${url} manually.`);
    });
    browser.unref();
  } catch {
    console.warn(`Warning: could not open the browser. Open ${url} manually.`);
  }
}

function serviceExit(record, name) {
  return record.exit.then((outcome) => ({
    type: "service-exit",
    name,
    ...outcome
  }));
}

function reportServiceExit(outcome) {
  if (outcome.error) {
    console.error(`\nThe ${outcome.name} could not start: ${outcome.error.message}`);
    return 1;
  }
  if (outcome.signal) {
    console.error(`\nThe ${outcome.name} stopped after signal ${outcome.signal}.`);
    return 1;
  }

  const code = outcome.code ?? 1;
  console.error(`\nThe ${outcome.name} stopped with exit code ${code}.`);
  return code;
}

async function main() {
  validateNodeVersion();
  const loadedLocalEnvironment = loadLocalEnvironment();
  const backendPort = parsePort(process.env.SERVER_PORT || "18080");
  const localBackendUrl = `http://127.0.0.1:${backendPort}`;
  const localFrontendUrl = `http://127.0.0.1:${FRONTEND_PORT}`;
  const frontendUrl =
    process.env.JAGENT_FRONTEND_URL || localFrontendUrl;
  const apiTarget = process.env.JAGENT_API_TARGET || localBackendUrl;
  const backendHealthUrl = `${localBackendUrl}/api/health`;
  const childEnv = {
    ...process.env,
    JAGENT_API_TARGET: apiTarget,
    SERVER_PORT: String(backendPort)
  };

  validateHttpUrl(apiTarget, "JAGENT_API_TARGET");
  validateHttpUrl(frontendUrl, "JAGENT_FRONTEND_URL");

  await requireCommand(
    JAVA_COMMAND,
    ["-version"],
    "Java 8 or newer is required."
  );
  await requireCommand(
    JAVAC_COMMAND,
    ["-version"],
    "A Java Development Kit (JDK) 8 or newer is required."
  );
  await requireCommand(MAVEN_COMMAND, ["--version"], "Maven 3 is required.");
  await requireCommand(NPM_COMMAND, ["--version"], "npm is required.");
  await assertLauncherPortsAvailable(backendPort);

  console.log("JAgentHarness coding launcher");
  if (loadedLocalEnvironment) {
    console.log(`Configuration: ${LOCAL_ENV_FILE}`);
  }
  console.log(`Backend API: ${apiTarget}`);
  console.log(`Frontend:    ${frontendUrl}\n`);

  if (!process.env.JAGENT_OPENAI_API_KEY) {
    console.warn(
      "Warning: JAGENT_OPENAI_API_KEY is not set; model calls will be unavailable.\n"
    );
  }

  const viteEntry = await ensureFrontendDependencies();
  await buildBackend();

  // The earlier check improves error reporting; this second check narrows the
  // race window while dependencies and the backend are being prepared.
  await assertLauncherPortsAvailable(backendPort);
  const backendJar = findBackendJar();

  console.log("\nStarting the coding backend...");
  const backend = spawnTracked(
    JAVA_COMMAND,
    ["-jar", backendJar, "--server.address=127.0.0.1"],
    {
      cwd: ROOT_DIR,
      env: childEnv,
      label: "coding backend"
    }
  );

  console.log("Starting the Vite frontend...");
  const frontend = spawnTracked(
    process.execPath,
    [
      viteEntry,
      "--host",
      "127.0.0.1",
      "--port",
      String(FRONTEND_PORT),
      "--strictPort",
      "--mode",
      "coding"
    ],
    {
      cwd: FRONTEND_DIR,
      env: childEnv,
      label: "Vite frontend"
    }
  );

  console.log("Press Control-C to stop both processes.\n");

  const firstServiceExit = Promise.race([
    serviceExit(backend, "coding backend"),
    serviceExit(frontend, "Vite frontend")
  ]);
  const startup = await Promise.race([
    waitForReadiness(localFrontendUrl, backendHealthUrl),
    firstServiceExit
  ]);

  if (startup.type === "service-exit") {
    if (shutdownPromise) {
      return;
    }
    await shutdown(reportServiceExit(startup));
    return;
  }

  if (startup.type === "ready") {
    console.log(`\nCoding console is ready at ${frontendUrl}`);
    if (
      String(process.env.JAGENT_OPEN_BROWSER || "true").toLowerCase() !==
      "false"
    ) {
      openBrowser(frontendUrl);
    }
  } else {
    console.warn(
      "\nThe coding console did not become ready within 90 seconds. Check the logs above."
    );
  }

  const stopped = await firstServiceExit;
  if (shutdownPromise) {
    return;
  }
  await shutdown(reportServiceExit(stopped));
}

registerSignalHandlers();
main().catch(async (error) => {
  if (shutdownPromise) {
    return;
  }
  console.error(`\nError: ${error instanceof Error ? error.message : error}`);
  await shutdown(1);
});
