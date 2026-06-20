import { createWriteStream } from "node:fs";
import { access, mkdir, readFile, rm, unlink, writeFile } from "node:fs/promises";
import { delimiter, resolve } from "node:path";
import { spawn } from "node:child_process";

const rootDirectory = resolve(new URL("..", import.meta.url).pathname);
const localDirectory = resolve(rootDirectory, ".local-dev");
const dataDirectory = resolve(localDirectory, "data");
const logDirectory = resolve(localDirectory, "logs");
const pidDirectory = resolve(localDirectory, "pids");
const backendPidPath = resolve(pidDirectory, "backend.pid");
const frontendPidPath = resolve(pidDirectory, "frontend.pid");
const backendUrl = "http://127.0.0.1:8080";
const frontendUrl = "http://127.0.0.1:5173";
const backendOnly = process.argv.includes("--backend");
let backendProcess;
let frontendProcess;
let stopping = false;

try {
  await verifyPrerequisites();
  await mkdir(dataDirectory, { recursive: true });
  await mkdir(logDirectory, { recursive: true });
  await mkdir(pidDirectory, { recursive: true });
  await ensureNoRecordedProcesses();
  await buildBackend();
  backendProcess = startBackend();
  await writeFile(backendPidPath, `${backendProcess.pid}\n`);
  await waitFor(`${backendUrl}/health`, "backend", backendProcess);

  if (backendOnly) {
    console.log(`Backend:  ${backendUrl}`);
    console.log(`Data:     .local-dev/data`);
    console.log("Press Ctrl+C to stop the backend");
    await waitForShutdown();
  } else {
    frontendProcess = startFrontend();
    await writeFile(frontendPidPath, `${frontendProcess.pid}\n`);
    await waitFor(frontendUrl, "frontend", frontendProcess);
    console.log(`Frontend: ${frontendUrl}`);
    console.log(`Backend:  ${backendUrl}`);
    console.log("Data:     .local-dev/data");
    console.log("Press Ctrl+C to stop both services");
    openBrowser();
    await waitForShutdown();
  }
} catch (error) {
  await fail(error);
}

async function verifyPrerequisites() {
  if (!process.version) throw new Error("Node.js is required.");
  await commandVersion("java", ["-version"], "Java 21 or newer is required.");
  await commandVersion("mvn", ["-version"], "Maven is required to build the backend.");
  await access(resolve(rootDirectory, "node_modules", "vite", "bin", "vite.js")).catch(() => {
    throw new Error("Node dependencies are missing. Run npm install first.");
  });
}

async function commandVersion(command, argumentsList, message) {
  await new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, argumentsList, { cwd: rootDirectory, stdio: "ignore" });
    child.on("error", () => rejectPromise(new Error(message)));
    child.on("exit", (code) => {
      if (code === 0) resolvePromise();
      else rejectPromise(new Error(message));
    });
  });
}

async function ensureNoRecordedProcesses() {
  for (const pidPath of [backendPidPath, frontendPidPath]) {
    const pid = await readFile(pidPath, "utf8")
      .then((value) => Number.parseInt(value.trim(), 10))
      .catch(() => undefined);
    if (Number.isInteger(pid) && isRunning(pid)) {
      throw new Error(
        "A recorded local development process is already running. Stop it with Ctrl+C or DEV_STOP.command."
      );
    }
    await unlink(pidPath).catch(() => undefined);
  }
}

async function buildBackend() {
  const buildLog = createWriteStream(resolve(logDirectory, "backend-build.log"), { flags: "w" });
  const result = await run("mvn", ["-q", `-Dmaven.repo.local=${resolve(localDirectory, "m2")}`, "-pl", "backend", "-am", "package", "-DskipTests"], buildLog);
  buildLog.end();
  if (result !== 0) throw new Error(`Backend build failed. See ${resolve(logDirectory, "backend-build.log")}`);
}

function startBackend() {
  return start(
    "java",
    [
      "-Dworkflow.http.port=8080",
      `-Dworkflow.local.data.dir=${dataDirectory}`,
      "-cp",
      ["backend", "domain", "contracts", "adapters"].map((module) => resolve(rootDirectory, module, "target", "classes")).join(delimiter),
      "com.sevenewf.workflow.backend.BackendApplication"
    ],
    "backend.log"
  );
}

function startFrontend() {
  return start(
    process.execPath,
    [
      resolve(rootDirectory, "node_modules", "vite", "bin", "vite.js"),
      resolve(rootDirectory, "frontend"),
      "--config",
      resolve(rootDirectory, "frontend", "vite.config.ts"),
      "--host",
      "127.0.0.1"
    ],
    "frontend.log"
  );
}

function start(command, argumentsList, logFile) {
  const log = createWriteStream(resolve(logDirectory, logFile), { flags: "w" });
  const child = spawn(command, argumentsList, { cwd: rootDirectory, stdio: ["ignore", "pipe", "pipe"] });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  child.once("error", (error) => {
    if (!stopping) void fail(new Error(`Could not start ${logFile}. ${error.message}`));
  });
  child.once("exit", (code) => {
    if (!stopping)
      void fail(new Error(`${logFile} stopped unexpectedly with code ${code}. See ${resolve(logDirectory, logFile)}`));
  });
  return child;
}

async function waitFor(url, serviceName, child) {
  for (let attempt = 0; attempt < 45; attempt += 1) {
    if (child.exitCode !== null) throw new Error(`${serviceName} stopped before becoming ready.`);
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {}
    await delay(1000);
  }
  throw new Error(`${serviceName} did not become ready at ${url}.`);
}

function openBrowser() {
  const browser = spawn("open", [frontendUrl], { detached: true, stdio: "ignore" });
  browser.unref();
}

async function waitForShutdown() {
  await new Promise((resolvePromise) => {
    process.once("SIGINT", resolvePromise);
    process.once("SIGTERM", resolvePromise);
  });
  await shutdown();
}

async function shutdown() {
  if (stopping) return;
  stopping = true;
  await Promise.all([stop(frontendProcess), stop(backendProcess)]);
  await Promise.all([unlink(frontendPidPath).catch(() => undefined), unlink(backendPidPath).catch(() => undefined)]);
}

async function stop(child) {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  await Promise.race([onceExit(child), delay(5000)]);
  if (child.exitCode === null) child.kill("SIGKILL");
}

async function fail(error) {
  console.error(`Development environment failed: ${error.message}`);
  await shutdown();
  process.exitCode = 1;
}

function run(command, argumentsList, log) {
  return new Promise((resolvePromise) => {
    const child = spawn(command, argumentsList, { cwd: rootDirectory, stdio: ["ignore", "pipe", "pipe"] });
    child.stdout.pipe(log);
    child.stderr.pipe(log);
    child.once("error", () => resolvePromise(1));
    child.once("exit", (code) => resolvePromise(code ?? 1));
  });
}

function isRunning(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function onceExit(child) {
  return new Promise((resolvePromise) => child.once("exit", resolvePromise));
}

function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}
