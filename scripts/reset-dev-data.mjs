import { readFile, rm } from "node:fs/promises";
import { resolve } from "node:path";

const rootDirectory = resolve(new URL("..", import.meta.url).pathname);
const localDirectory = resolve(rootDirectory, ".local-dev");
const pidPaths = [
  resolve(localDirectory, "pids", "backend.pid"),
  resolve(localDirectory, "pids", "frontend.pid")
];

for (const pidPath of pidPaths) {
  const pid = await readFile(pidPath, "utf8")
    .then((value) => Number.parseInt(value.trim(), 10))
    .catch(() => undefined);
  if (Number.isInteger(pid) && isRunning(pid)) {
    throw new Error("Stop npm run dev before resetting local development data.");
  }
}

await rm(resolve(localDirectory, "data"), { recursive: true, force: true });
console.log("Removed .local-dev/data. The next npm run dev will seed synthetic data.");

function isRunning(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
