/**
 * review-before-build.ts — pi extension: reminds user to review code before gradle build/test commands.
 *
 * Task 2: Core utility functions (no event handlers yet).
 * Produces pure functions consumed by Task 3's event handlers.
 */

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve, sep } from "node:path";
import { glob } from "glob";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { isBashToolResult, isToolCallEventType } from "@earendil-works/pi-coding-agent";

// ── 1. isBuildCommand ──────────────────────────────────────────────────────

/**
 * Detect whether a shell command is a Gradle build/test command.
 * Matches commands containing "gradle" and one of: test, build, assemble, check.
 */
function isBuildCommand(command: string): boolean {
  const lower = command.toLowerCase();
  if (!lower.includes("gradle")) return false;
  const buildKeywords = /\b(test|build|assemble|check)/;
  return buildKeywords.test(lower);
}

// ── 2. isSimpleCommand ─────────────────────────────────────────────────────

/**
 * Determine whether a command is "simple" (safe to inject into without shell
 * metacharacter issues). A simple command:
 *  - Has no newlines
 *  - Contains no pipe/redirect/chaining operators (| ; & < >)
 *  - Does not start with a variable assignment (FOO=bar ...)
 *  - Contains no parentheses (subshells)
 *  - Contains no quotes (single, double, backtick)
 *  - Contains no backslashes
 */
function isSimpleCommand(command: string): boolean {
  if (command.includes("\n")) return false;
  if (/[|;&<>]/.test(command)) return false;
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(command.trim())) return false;
  if (/[()]/.test(command)) return false;
  if (/['"`]/.test(command)) return false;
  if (/\\/.test(command)) return false;
  return true;
}

// ── 3. scanSourceFiles ────────────────────────────────────────────────────

/**
 * Scan the project directory for source files (.kt + gradle configs) and
 * return a Map of POSIX-relative-path → SHA-256 hex hash.
 *
 * Non-readable files are silently skipped.
 */
async function scanSourceFiles(cwd: string): Promise<Map<string, string>> {
  const snapshot = new Map<string, string>();
  const patterns = [
    "app/src/main/java/**/*.kt",
    "app/src/main/kotlin/**/*.kt",
    "app/src/test/**/*.kt",
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
  ];
  const ignorePatterns = ["**/build/**", "**/.gradle/**"];

  const files = await glob(patterns, {
    cwd,
    ignore: ignorePatterns,
    nodir: true,
    dot: false,
  });

  for (const file of files) {
    try {
      const absPath = resolve(cwd, file);
      const content = await readFile(absPath);
      const hash = createHash("sha256").update(content).digest("hex");
      const posixPath = file.split(sep).join("/");
      snapshot.set(posixPath, hash);
    } catch {
      // skip unreadable files
    }
  }

  return snapshot;
}

// ── 4. diffAgainst ─────────────────────────────────────────────────────────

/**
 * Compare two snapshots (current vs last) and return a sorted list of
 * file paths that were added, changed, or removed.
 */
function diffAgainst(
  current: Map<string, string>,
  last: Map<string, string>
): string[] {
  const changed: string[] = [];

  for (const [path, hash] of current) {
    const lastHash = last.get(path);
    if (lastHash === undefined || lastHash !== hash) {
      changed.push(path);
    }
  }

  for (const path of last.keys()) {
    if (!current.has(path)) {
      changed.push(path);
    }
  }

  return changed.sort();
}

// ── 5. shellEscape ─────────────────────────────────────────────────────────

/**
 * Escape text for safe use in a bash single-quoted string.
 * Strips control characters, escapes embedded single quotes, and wraps in
 * single quotes.
 */
function shellEscape(text: string): string {
  const cleaned = text.replace(
    /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g,
    " "
  );
  const escaped = cleaned.replace(/'/g, "'\\''");
  return `'${escaped}'`;
}

// ── 6. buildReminderText ───────────────────────────────────────────────────

/**
 * Generate a reminder message to review changed files before building.
 *
 * @returns `{ text, hasChanges }` — `hasChanges` is false when no files changed.
 */
function buildReminderText(
  changedFiles: string[]
): { text: string; hasChanges: boolean } {
  const MAX_FILES = 10;
  const MAX_PATH_LENGTH = 120;
  const MAX_TOTAL_LENGTH = 2000;

  if (changedFiles.length === 0) {
    return {
      text: "[审查提醒] 自上次构建尝试以来无新代码变化，可直接构建。",
      hasChanges: false,
    };
  }

  const truncatePath = (p: string): string =>
    p.length > MAX_PATH_LENGTH ? p.slice(0, MAX_PATH_LENGTH - 1) + "…" : p;

  const files = changedFiles.slice(0, MAX_FILES);
  const lines = files.map((f) => `  - ${truncatePath(f)}`);
  if (changedFiles.length > MAX_FILES) {
    lines.push(
      `  - … 以及 ${
        changedFiles.length - MAX_FILES
      } 个文件（较多，建议优先审查本次会话改动的文件）`
    );
  }

  let text = `[审查提醒] 检测到构建命令。本次自上次构建尝试以来变化的新代码：\n${lines.join(
    "\n"
  )}\n按项目约定，构建前应先审查这些文件。`;

  if (text.length > MAX_TOTAL_LENGTH) {
    text = text.slice(0, MAX_TOTAL_LENGTH - 3) + "…";
  }

  return { text, hasChanges: true };
}

export {
  isBuildCommand,
  isSimpleCommand,
  scanSourceFiles,
  diffAgainst,
  shellEscape,
  buildReminderText,
};

// ── 7. Event handlers — pi extension default export ────────────────────────

export default function (pi: ExtensionAPI) {
  // === 内存状态 ===
  let snapshot = new Map<string, string>();
  const injectedCommands = new Map<string, string>();

  // === 软拦截层：注入系统提示 ===
  pi.on("before_agent_start", async (event, _ctx) => {
    if (isBuildCommand(event.prompt)) {
      return {
        systemPrompt:
          event.systemPrompt +
          "\n\n[代码审查约定] 运行构建/测试命令前，应先审查自上次构建尝试以来变化的代码文件；审查通过后再执行构建。硬提醒层会在构建命令前注入 echo 提醒，请留意命令输出中的审查提醒。",
      };
    }
  });

  // === 硬提醒层：检测构建命令 → 注入提醒/notify → 更新快照 ===
  pi.on("tool_call", async (event, ctx) => {
    if (!isToolCallEventType("bash", event)) return;
    if (!isBuildCommand(event.input.command)) return;

    const cwd = ctx.cwd;

    try {
      const currentSnapshot = await scanSourceFiles(cwd);
      const changedFiles = diffAgainst(currentSnapshot, snapshot);
      const { text: reminderText, hasChanges } = buildReminderText(changedFiles);

      if (isSimpleCommand(event.input.command)) {
        const escapedReminder = shellEscape(reminderText);
        event.input.command = `echo ${escapedReminder} && ${event.input.command}`;
      } else {
        const action = hasChanges ? "建议先审查新代码" : "可直接构建";
        ctx.ui.notify(`[审查提醒] 检测到构建命令（复杂命令，未修改）。${action}。`, "info");
      }

      // 更新快照（"上次构建尝试"基线）
      snapshot = currentSnapshot;
      injectedCommands.set(event.toolCallId, event.input.command);

    } catch (err) {
      ctx.ui.notify(
        `[审查提醒] 钩子异常（${err instanceof Error ? err.message : String(err)}），已跳过本次审查提醒。`,
        "error"
      );
    }
  });

  // === 构建失败反馈闭环 ===
  pi.on("tool_result", async (event, ctx) => {
    if (!isBashToolResult(event)) return;
    if (!injectedCommands.has(event.toolCallId)) return;

    injectedCommands.delete(event.toolCallId);

    // 仅依赖 event.isError（details 在运行时为 undefined）
    if (event.isError === true) {
      const outputText = Array.isArray(event.content)
        ? event.content.map((c) => ("text" in c ? c.text : "")).join("\n")
        : String(event.content ?? "");

      const truncated = outputText.length > 3000
        ? outputText.slice(0, 3000) + "\n…（输出已截断）"
        : outputText;

      pi.sendUserMessage(
        `构建失败，请修复后重试。\n\n构建输出：\n\`\`\`\n${truncated}\n\`\`\``,
        { deliverAs: "followUp" }
      );
    }
  });
}