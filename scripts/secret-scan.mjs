import { readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";

const scannedFiles = execFileSync(
  "git",
  ["ls-files", "--cached", "--others", "--exclude-standard"],
  {
    encoding: "utf8"
  }
)
  .split("\n")
  .filter(Boolean)
  .filter((file) => !file.endsWith("package-lock.json"))
  .filter(
    (file) =>
      file !==
      "architecture-tests/src/test/java/com/sevenewf/workflow/architecture/ForbiddenProductionIntegrationTest.java"
  )
  .filter((file) => file !== "scripts/secret-scan.mjs");

const patterns = [
  { name: "private key", pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----/ },
  { name: "aws access key", pattern: /AKIA[0-9A-Z]{16}/ },
  {
    name: "generic secret assignment",
    pattern: /\b(secret|password|token|api[_-]?key)\s*=\s*['"][^'"]{8,}['"]/i
  },
  { name: "oracle jdbc url", pattern: /jdbc:oracle:thin/i }
];

const findings = [];
for (const file of scannedFiles) {
  const contents = await readFile(file, "utf8").catch(() => "");
  for (const { name, pattern } of patterns) {
    if (pattern.test(contents)) {
      findings.push(`${file}: ${name}`);
    }
  }
}

if (findings.length > 0) {
  console.error("Potential secret findings:");
  for (const finding of findings) {
    console.error(`- ${finding}`);
  }
  process.exit(1);
}

console.log(`Secret scan passed for ${scannedFiles.length} source files.`);
