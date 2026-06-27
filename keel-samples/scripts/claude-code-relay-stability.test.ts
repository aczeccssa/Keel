import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const scriptPath = new URL("./claude-code-relay-stability.ts", import.meta.url);

function runScript(args: string[], envOverrides: NodeJS.ProcessEnv = {}) {
  return spawnSync(
    process.execPath,
    ["--experimental-strip-types", scriptPath.pathname, ...args],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        ANTHROPIC_API_KEY: "test-key",
        ...envOverrides,
      },
    }
  );
}

function createFakeClaude() {
  const tempDir = mkdtempSync(join(tmpdir(), "fake-claude-"));
  const captureFile = join(tempDir, "capture.json");
  const runnerFile = join(tempDir, "fake-claude-runner.js");
  const claudeFile = join(tempDir, "claude");

  writeFileSync(
    runnerFile,
    `
const fs = require("node:fs");
const payload = {
  argv: process.argv.slice(2),
  env: {
    ANTHROPIC_BASE_URL: process.env.ANTHROPIC_BASE_URL ?? null,
    ANTHROPIC_API_KEY: process.env.ANTHROPIC_API_KEY ?? null,
  },
};
fs.writeFileSync(process.env.CAPTURE_FILE, JSON.stringify(payload, null, 2));
process.stdout.write(JSON.stringify({ type: "result", result: "OK" }));
`.trimStart()
  );
  writeFileSync(
    claudeFile,
    `#!/bin/sh
node "${runnerFile}" "$@"
`
  );
  chmodSync(claudeFile, 0o755);

  return {
    captureFile,
    env: {
      PATH: `${tempDir}:${process.env.PATH ?? ""}`,
      CAPTURE_FILE: captureFile,
    },
    cleanup() {
      rmSync(tempDir, { recursive: true, force: true });
    },
  };
}

test("dry-run skip-alias-patch does not require admin patch planning", () => {
  const result = runScript([
    "single",
    "--dry-run",
    "--skip-alias-patch",
    "--model",
    "claude-opus-4-8",
  ]);

  assert.equal(result.status, 0, result.stderr);

  const payload = JSON.parse(result.stdout);
  assert.equal(payload.patch.mode, "none");
  assert.match(String(payload.patch.note), /skip alias patch/i);
  assert.equal(payload.summary.channelId, null);
  assert.equal(payload.summary.runs, 1);
});

test("single mode runs Claude with project/local settings only so relay env is not shadowed by global config", () => {
  const fakeClaude = createFakeClaude();

  try {
    const result = runScript(
      [
        "single",
        "--skip-alias-patch",
        "--prompt",
        "Reply with exactly: OK",
      ],
      fakeClaude.env
    );

    assert.equal(result.status, 0, result.stderr);

    const payload = JSON.parse(result.stdout);
    assert.equal(payload.successCount, 1);
    assert.equal(payload.failureCount, 0);

    const capture = JSON.parse(readFileSync(fakeClaude.captureFile, "utf8"));
    assert.deepEqual(capture.argv, [
      "--bare",
      "--setting-sources",
      "project,local",
      "-p",
      "Reply with exactly: OK",
      "--model",
      "claude-opus-4-8",
      "--output-format",
      "json",
      "--max-budget-usd",
      "0.1",
    ]);
    assert.equal(capture.env.ANTHROPIC_BASE_URL, "http://localhost:8080/api/plugins/airelay");
    assert.equal(capture.env.ANTHROPIC_API_KEY, "test-key");
  } finally {
    fakeClaude.cleanup();
  }
});

test("estimated max spend above 10 USD is rejected before any Claude invocation", () => {
  const fakeClaude = createFakeClaude();

  try {
    const result = runScript(
      [
        "stability",
        "--skip-alias-patch",
        "--runs",
        "50",
        "--max-budget-usd",
        "0.25",
      ],
      fakeClaude.env
    );

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /estimated max spend/i);
    assert.match(result.stderr, /\$10/i);
    assert.throws(() => readFileSync(fakeClaude.captureFile, "utf8"), /ENOENT/);
  } finally {
    fakeClaude.cleanup();
  }
});
