#!/usr/bin/env node

const { spawn } = require("node:child_process");

type Mode = "single" | "stability";

type CliOptions = {
  mode: Mode;
  relayBase: string;
  apiKey: string | null;
  adminToken: string | null;
  groupId: string;
  model: string;
  aliasName: string;
  channelId: string | null;
  runs: number;
  dryRun: boolean;
  skipAliasPatch: boolean;
  prompt: string;
  maxBudgetUsd: number;
  estimatedMaxSpendUsd: number;
  runsWasCapped: boolean;
};

type GroupAliasTargetView = {
  model?: string;
  channelId?: string | null;
};

type GroupAliasView = {
  aliasId?: string;
  aliasName?: string;
  targetModels?: string[];
  enabled?: boolean;
  creditMultiplier?: number | null;
  targets?: GroupAliasTargetView[];
  routingPolicy?: string;
};

type GroupView = {
  groupId?: string;
  aliasRoutes?: GroupAliasView[];
};

type ChannelModelView = {
  publicModelName?: string;
  enabled?: boolean;
};

type ChannelView = {
  channelId?: string;
  name?: string;
  protocol?: string;
  enabled?: boolean;
  models?: ChannelModelView[];
};

type RunResult = {
  runIndex: number;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  elapsedMs: number;
  spawnError: string | null;
  success: boolean;
};

type Summary = {
  mode: Mode;
  groupId: string;
  model: string;
  aliasName: string;
  channelId: string | null;
  runs: number;
  successCount: number;
  failureCount: number;
  avgElapsedMs: number;
  estimatedMaxSpendUsd: number;
  runsWasCapped: boolean;
  dryRun: boolean;
  results: RunResult[];
};

type HttpJsonRequest = {
  method?: string;
  body?: unknown;
  adminToken?: string | null;
};

type AliasPatchContext = {
  channelId: string;
  originalAliasRoutes: GroupAliasView[];
  patchedAliasRoutes: Array<Record<string, unknown>>;
  restoreAliasRoutes: Array<Record<string, unknown>>;
};

const DEFAULT_RELAY_BASE = "http://localhost:8080/api/plugins/airelay";
const DEFAULT_GROUP_ID = "default";
const DEFAULT_MODEL = "claude-opus-4-8";
const DEFAULT_PROMPT = "Reply with exactly: OK";
const DEFAULT_MAX_BUDGET_USD = 0.1;
const DEFAULT_STABILITY_RUNS = 5;
const CLAUDE_SETTING_SOURCES = "project,local";
const MAX_ESTIMATED_TOTAL_BUDGET_USD = 10;
const RUNS_HARD_CAP = 50;
const BOOLEAN_FLAGS = new Set(["--dry-run", "--skip-alias-patch"]);
const VALUE_FLAGS = new Set([
  "--relay-base",
  "--group-id",
  "--model",
  "--alias",
  "--channel-id",
  "--runs",
  "--prompt",
  "--max-budget-usd",
  "--api-key",
  "--admin-token",
]);

void main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  const commandPreview = buildCommandPreview(options);

  if (options.dryRun) {
    printJson({
      dryRun: true,
      mode: options.mode,
      patch: {
        relayBase: options.relayBase,
        groupId: options.groupId,
        aliasName: options.aliasName,
        model: options.model,
        channelId: options.channelId,
        mode: options.skipAliasPatch ? "none" : "alias-route",
        note: options.skipAliasPatch
          ? "Skip alias patch and send Claude Code traffic directly through the relay with the provided API key."
          : options.channelId
            ? "Would replace the target alias route with a single ANTHROPIC_MESSAGES target on the explicit channel."
            : "Would auto-discover one ANTHROPIC_MESSAGES channel advertising the target model, then replace the target alias route.",
      },
      claudeCommand: commandPreview,
      summary: {
        mode: options.mode,
        groupId: options.groupId,
        model: options.model,
        aliasName: options.aliasName,
        channelId: options.channelId,
        runs: options.mode === "single" ? 1 : options.runs,
        successCount: 0,
        failureCount: 0,
        avgElapsedMs: 0,
        estimatedMaxSpendUsd: options.estimatedMaxSpendUsd,
        runsWasCapped: options.runsWasCapped,
        dryRun: true,
      },
    });
    return;
  }

  if (!options.apiKey) {
    throw new Error("Missing Anthropic API key. Pass --api-key or set ANTHROPIC_API_KEY.");
  }

  const aliasContext = options.skipAliasPatch ? null : await applyAliasPatch(options);
  let executionError: unknown = null;

  try {
    const results =
      options.mode === "single"
        ? [await runClaudeCommand(options, 1)]
        : await runStabilitySequence(options);
    const summary = summarize(options, aliasContext?.channelId ?? null, results);
    printJson(summary);
  } catch (error: unknown) {
    executionError = error;
  } finally {
    if (!aliasContext) {
      if (executionError) {
        throw executionError;
      }
      return;
    }
    try {
      await restoreAliasPatch(options, aliasContext);
    } catch (restoreError: unknown) {
      const restoreMessage = restoreError instanceof Error ? restoreError.message : String(restoreError);
      if (executionError) {
        const runMessage = executionError instanceof Error ? executionError.message : String(executionError);
        throw new Error(`${runMessage}\nAdditionally failed to restore aliasRoutes: ${restoreMessage}`);
      }
      throw restoreError;
    }
  }

  if (executionError) {
    throw executionError;
  }
}

function parseArgs(argv: string[]): CliOptions {
  if (argv.length === 0 || argv.includes("--help") || argv.includes("-h")) {
    printUsageAndExit(0);
  }

  const modeText = argv[0];
  if (modeText !== "single" && modeText !== "stability") {
    throw new Error(`First argument must be "single" or "stability". Received: ${modeText}`);
  }

  const values = new Map<string, string>();
  const flags = new Set<string>();

  for (let index = 1; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      throw new Error(`Unexpected positional argument: ${token}`);
    }
    if (BOOLEAN_FLAGS.has(token)) {
      flags.add(token);
      continue;
    }
    if (!VALUE_FLAGS.has(token)) {
      throw new Error(`Unknown flag: ${token}`);
    }

    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Flag ${token} requires a value.`);
    }
    values.set(token, value);
    index += 1;
  }

  const relayBase = normalizeRelayBase(values.get("--relay-base") ?? DEFAULT_RELAY_BASE);
  const model = nonEmpty(values.get("--model") ?? DEFAULT_MODEL, "--model");
  const aliasName = nonEmpty(values.get("--alias") ?? model, "--alias");
  const groupId = nonEmpty(values.get("--group-id") ?? DEFAULT_GROUP_ID, "--group-id");
  const prompt = nonEmpty(values.get("--prompt") ?? DEFAULT_PROMPT, "--prompt");
  const maxBudgetUsd = parsePositiveNumber(values.get("--max-budget-usd") ?? String(DEFAULT_MAX_BUDGET_USD), "--max-budget-usd");
  const dryRun = flags.has("--dry-run");
  const skipAliasPatch = flags.has("--skip-alias-patch");
  const requestedRuns = modeText === "single"
    ? 1
    : parsePositiveInteger(values.get("--runs") ?? String(DEFAULT_STABILITY_RUNS), "--runs");
  const cappedRuns = Math.min(requestedRuns, RUNS_HARD_CAP);
  const runsWasCapped = requestedRuns !== cappedRuns;
  const estimatedMaxSpendUsd = cappedRuns * maxBudgetUsd;

  if (runsWasCapped) {
    console.error(`Requested runs ${requestedRuns} exceeded hard cap ${RUNS_HARD_CAP}; using ${cappedRuns}.`);
  }
  if (estimatedMaxSpendUsd > MAX_ESTIMATED_TOTAL_BUDGET_USD) {
    throw new Error(
      `Estimated max spend $${estimatedMaxSpendUsd.toFixed(2)} exceeds hard cap $${MAX_ESTIMATED_TOTAL_BUDGET_USD.toFixed(2)}. ` +
      "Reduce --runs or --max-budget-usd."
    );
  }

  return {
    mode: modeText,
    relayBase,
    apiKey: values.get("--api-key") ?? process.env.ANTHROPIC_API_KEY ?? null,
    adminToken: values.get("--admin-token") ?? process.env.AIRELAY_ADMIN_TOKEN ?? null,
    groupId,
    model,
    aliasName,
    channelId: values.get("--channel-id") ?? null,
    runs: cappedRuns,
    dryRun,
    skipAliasPatch,
    prompt,
    maxBudgetUsd,
    estimatedMaxSpendUsd,
    runsWasCapped,
  };
}

function normalizeRelayBase(value: string): string {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch (error: unknown) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`Invalid --relay-base URL: ${value}. ${detail}`);
  }
  const pathname = parsed.pathname.replace(/\/+$/, "");
  parsed.pathname = pathname || "/";
  return parsed.toString().replace(/\/$/, "");
}

function nonEmpty(value: string, flagName: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    throw new Error(`${flagName} cannot be empty.`);
  }
  return trimmed;
}

function parsePositiveNumber(value: string, flagName: string): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${flagName} must be a positive number. Received: ${value}`);
  }
  return parsed;
}

function parsePositiveInteger(value: string, flagName: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${flagName} must be a positive integer. Received: ${value}`);
  }
  return parsed;
}

function printUsageAndExit(code: number): never {
  const usage = `
Usage:
  node --experimental-strip-types keel-samples/scripts/claude-code-relay-stability.ts single [options]
  node --experimental-strip-types keel-samples/scripts/claude-code-relay-stability.ts stability [options]

Options:
  --relay-base <url>        Relay base URL. Default: ${DEFAULT_RELAY_BASE}
  --group-id <id>           Routing group ID. Default: ${DEFAULT_GROUP_ID}
  --model <name>            Claude model passed to \`claude --model\`. Default: ${DEFAULT_MODEL}
  --alias <name>            Alias route to patch. Default: same as --model
  --channel-id <id>         Explicit channel ID to pin for the alias
  --runs <n>                Stability runs. Default: ${DEFAULT_STABILITY_RUNS}; hard cap: ${RUNS_HARD_CAP}
  --prompt <text>           Prompt for \`claude -p\`. Default: ${DEFAULT_PROMPT}
  --max-budget-usd <n>      Budget passed to Claude. Default: ${DEFAULT_MAX_BUDGET_USD}
                            Estimated max spend hard cap: $${MAX_ESTIMATED_TOTAL_BUDGET_USD.toFixed(2)}
  --api-key <key>           Anthropic API key for the Claude subprocess
  --admin-token <token>     Optional Bearer token for relay admin endpoints
  --skip-alias-patch        Skip admin alias routing changes and use the API key's existing relay routing
  --dry-run                 Print the alias patch plan and Claude command without HTTP or Claude requests
  --help                    Show this help

Environment:
  ANTHROPIC_API_KEY         Default API key for the Claude subprocess
  AIRELAY_ADMIN_TOKEN       Optional Bearer token for relay admin endpoints
`.trim();

  if (code === 0) {
    console.log(usage);
  } else {
    console.error(usage);
  }
  process.exit(code);
}

function buildCommandPreview(options: CliOptions): { env: Record<string, string>; command: string; args: string[]; printable: string } {
  const args = [
    "--bare",
    "--setting-sources",
    CLAUDE_SETTING_SOURCES,
    "-p",
    options.prompt,
    "--model",
    options.model,
    "--output-format",
    "json",
    "--max-budget-usd",
    String(options.maxBudgetUsd),
  ];

  const env = {
    ANTHROPIC_BASE_URL: options.relayBase,
    ANTHROPIC_API_KEY: options.apiKey ? "[redacted]" : "[missing]",
  };

  return {
    env,
    command: "claude",
    args,
    printable: `ANTHROPIC_BASE_URL=${shellQuote(env.ANTHROPIC_BASE_URL)} ANTHROPIC_API_KEY=${shellQuote(env.ANTHROPIC_API_KEY)} claude ${args.map(shellQuote).join(" ")}`,
  };
}

async function applyAliasPatch(options: CliOptions): Promise<AliasPatchContext> {
  const groupsResponse = await fetchJson(`${options.relayBase}/admin/groups`, {
    adminToken: options.adminToken,
  });
  const channelsResponse = await fetchJson(`${options.relayBase}/admin/channels`, {
    adminToken: options.adminToken,
  });

  const groups = asArray(groupsResponse, "groups response", "groups") as GroupView[];
  const channels = asArray(channelsResponse, "channels response", "channels") as ChannelView[];
  const group = groups.find((item) => item.groupId === options.groupId);

  if (!group) {
    const available = groups.map((item) => item.groupId).filter(Boolean);
    throw new Error(`Group ${options.groupId} not found in /admin/groups. Available groups: ${available.join(", ") || "<none>"}`);
  }

  const originalAliasRoutes = Array.isArray(group.aliasRoutes) ? group.aliasRoutes : [];
  const targetAlias = originalAliasRoutes.find((route) => route.aliasName === options.aliasName);
  if (!targetAlias) {
    const availableAliases = originalAliasRoutes.map((route) => route.aliasName).filter(Boolean);
    throw new Error(
      `Alias ${options.aliasName} not found in group ${options.groupId}. Available aliases: ${availableAliases.join(", ") || "<none>"}`
    );
  }

  const channel = resolveChannel(channels, options);
  const restoreAliasRoutes = originalAliasRoutes.map(toAliasUpsert);
  const patchedAliasRoutes = originalAliasRoutes.map((route) => {
    const upsert = toAliasUpsert(route);
    if (route.aliasName !== options.aliasName) {
      return upsert;
    }
    return {
      ...upsert,
      targets: [{ model: options.model, channelId: channel.channelId }],
    };
  });

  await fetchJson(`${options.relayBase}/admin/groups/${encodeURIComponent(options.groupId)}/aliases`, {
    method: "PUT",
    body: { aliasRoutes: patchedAliasRoutes },
    adminToken: options.adminToken,
  });

  return {
    channelId: channel.channelId as string,
    originalAliasRoutes,
    patchedAliasRoutes,
    restoreAliasRoutes,
  };
}

function resolveChannel(channels: ChannelView[], options: CliOptions): ChannelView {
  if (options.channelId) {
    const explicit = channels.find((channel) => channel.channelId === options.channelId);
    if (!explicit) {
      const known = channels.map((channel) => channel.channelId).filter(Boolean);
      throw new Error(`Channel ${options.channelId} not found in /admin/channels. Available channels: ${known.join(", ") || "<none>"}`);
    }
    assertChannelSupportsModel(explicit, options.model);
    return explicit;
  }

  const candidates = channels.filter((channel) => isCandidateAnthropicChannel(channel, options.model));
  if (candidates.length === 0) {
    throw new Error(`No enabled ANTHROPIC_MESSAGES channel advertises model ${options.model}.`);
  }
  if (candidates.length > 1) {
    const labels = candidates.map((channel) => `${channel.channelId}:${channel.name ?? "<unnamed>"}`);
    throw new Error(`Multiple candidate channels found for model ${options.model}. Pass --channel-id explicitly. Candidates: ${labels.join(", ")}`);
  }
  return candidates[0];
}

function isCandidateAnthropicChannel(channel: ChannelView, model: string): boolean {
  return channel.enabled !== false &&
    channel.protocol === "ANTHROPIC_MESSAGES" &&
    channelHasModel(channel, model);
}

function assertChannelSupportsModel(channel: ChannelView, model: string): void {
  if (channel.enabled === false) {
    throw new Error(`Channel ${channel.channelId} is disabled; choose an enabled channel.`);
  }
  if (channel.protocol !== "ANTHROPIC_MESSAGES") {
    throw new Error(`Channel ${channel.channelId} uses protocol ${channel.protocol}; expected ANTHROPIC_MESSAGES.`);
  }
  if (!channelHasModel(channel, model)) {
    const models = (channel.models ?? [])
      .filter((item) => item.enabled !== false)
      .map((item) => item.publicModelName)
      .filter(Boolean);
    throw new Error(`Channel ${channel.channelId} does not advertise model ${model}. Enabled models: ${models.join(", ") || "<none>"}`);
  }
}

function channelHasModel(channel: ChannelView, model: string): boolean {
  return (channel.models ?? []).some((item) => item.enabled !== false && item.publicModelName === model);
}

function toAliasUpsert(route: GroupAliasView): Record<string, unknown> {
  const targetModels = Array.isArray(route.targetModels)
    ? route.targetModels.filter((value): value is string => typeof value === "string" && value.trim().length > 0)
    : [];
  const targets = Array.isArray(route.targets)
    ? route.targets
        .filter((item) => typeof item?.model === "string" && item.model.trim().length > 0)
        .map((item) => ({ model: item.model, channelId: item.channelId ?? null }))
    : targetModels.map((model) => ({ model, channelId: null }));

  return {
    aliasName: route.aliasName,
    targetModels,
    enabled: route.enabled !== false,
    creditMultiplier: route.creditMultiplier ?? null,
    targets,
    routingPolicy: route.routingPolicy ?? "ORDERED_FAILOVER",
  };
}

async function restoreAliasPatch(options: CliOptions, context: AliasPatchContext): Promise<void> {
  await fetchJson(`${options.relayBase}/admin/groups/${encodeURIComponent(options.groupId)}/aliases`, {
    method: "PUT",
    body: { aliasRoutes: context.restoreAliasRoutes },
    adminToken: options.adminToken,
  });
}

async function runStabilitySequence(options: CliOptions): Promise<RunResult[]> {
  const results: RunResult[] = [];
  for (let runIndex = 1; runIndex <= options.runs; runIndex += 1) {
    results.push(await runClaudeCommand(options, runIndex));
  }
  return results;
}

async function runClaudeCommand(options: CliOptions, runIndex: number): Promise<RunResult> {
  const startedAt = Date.now();
  const args = [
    "--bare",
    "--setting-sources",
    CLAUDE_SETTING_SOURCES,
    "-p",
    options.prompt,
    "--model",
    options.model,
    "--output-format",
    "json",
    "--max-budget-usd",
    String(options.maxBudgetUsd),
  ];

  const env = {
    ...process.env,
    ANTHROPIC_BASE_URL: options.relayBase,
    ANTHROPIC_API_KEY: options.apiKey as string,
  };

  return await new Promise<RunResult>((resolve) => {
    const child = spawn("claude", args, {
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";
    let settled = false;
    let spawnError: string | null = null;

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });

    child.on("error", (error: Error) => {
      spawnError = error.message;
      if (settled) {
        return;
      }
      settled = true;
      const elapsedMs = Date.now() - startedAt;
      resolve({
        runIndex,
        exitCode: null,
        stdout,
        stderr,
        elapsedMs,
        spawnError,
        success: false,
      });
    });

    child.on("close", (exitCode: number | null) => {
      if (settled) {
        return;
      }
      settled = true;
      const elapsedMs = Date.now() - startedAt;
      resolve({
        runIndex,
        exitCode,
        stdout,
        stderr,
        elapsedMs,
        spawnError,
        success: exitCode === 0 && spawnError === null,
      });
    });
  });
}

function summarize(options: CliOptions, channelId: string | null, results: RunResult[]): Summary {
  const successCount = results.filter((result) => result.success).length;
  const totalElapsed = results.reduce((sum, result) => sum + result.elapsedMs, 0);
  const runs = results.length;

  return {
    mode: options.mode,
    groupId: options.groupId,
    model: options.model,
    aliasName: options.aliasName,
    channelId,
    runs,
    successCount,
    failureCount: runs - successCount,
    avgElapsedMs: runs === 0 ? 0 : Math.round(totalElapsed / runs),
    estimatedMaxSpendUsd: options.estimatedMaxSpendUsd,
    runsWasCapped: options.runsWasCapped,
    dryRun: options.dryRun,
    results,
  };
}

async function fetchJson(url: string, request: HttpJsonRequest): Promise<unknown> {
  const headers: Record<string, string> = {
    Accept: "application/json",
  };
  if (request.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (request.adminToken) {
    headers.Authorization = `Bearer ${request.adminToken}`;
  }

  const response = await fetch(url, {
    method: request.method ?? "GET",
    headers,
    body: request.body === undefined ? undefined : JSON.stringify(request.body),
  });

  const rawText = await response.text();
  if (!response.ok) {
    throw new Error(
      `HTTP ${response.status} ${response.statusText} for ${request.method ?? "GET"} ${url}\n` +
      `Response body: ${truncate(rawText, 1200)}`
    );
  }

  try {
    return rawText ? JSON.parse(rawText) : {};
  } catch (error: unknown) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new Error(`Failed to parse JSON from ${url}: ${detail}\nRaw body: ${truncate(rawText, 1200)}`);
  }
}

function asArray(payload: unknown, containerName: string, key: string): unknown[] {
  if (!payload || typeof payload !== "object" || !Array.isArray((payload as Record<string, unknown>)[key])) {
    throw new Error(`Expected ${containerName}.${key} to be an array.`);
  }
  return (payload as Record<string, unknown>)[key] as unknown[];
}

function truncate(value: string, maxLength: number): string {
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, maxLength)}...<truncated>`;
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\"'\"'`)}'`;
}

function printJson(value: unknown): void {
  console.log(JSON.stringify(value, null, 2));
}
