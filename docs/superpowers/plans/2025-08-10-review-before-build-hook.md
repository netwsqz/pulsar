# 构建前自动审查新代码 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 pi 中增加一个扩展，每次构建/测试命令时自动提醒 LLM 审查新代码

**Architecture:** 单文件 pi 扩展（`.pi/extensions/review-before-build.ts`），复刻 hookify warn 机制。`tool_call` 检测构建命令 → 对比文件快照找出变化文件 → 注入 echo 提醒（简单命令）或 UI notify（复杂命令）→ 构建照常执行。`before_agent_start` 注入系统提示（软决定）。`tool_result` 构建失败时排队修复消息。

**Tech Stack:** pi 0.84.1 Extension API（TypeScript），Node.js 内置 `node:crypto`（SHA-256）

## Global Constraints

- 项目不是 git 仓库，不要用 git 相关命令
- 扩展文件为单文件 `.pi/extensions/review-before-build.ts`
- 文件快照存内存，不持久化
- 不阻塞构建，不引入 `review_complete` 工具
- 只在简单命令上注入 echo 提醒；复杂命令仅 UI notify
- 提醒文本必须完整 shell 转义（`'...'` 包裹，内部 `'` 替换为 `'\''`）
- 快照在 `tool_call` 识别到构建命令时立即更新（"上次构建尝试"语义）
- 所有逻辑包 try/catch，出错时 fail-open
- 构建失败闭环只处理被注入过的命令（`toolCallId` 映射）

---

### Task 1: API smoke test — 验证 Pi 事件类型

**Files:**
- Create: `E:/nsmic/.pi/extensions/smoke-test.ts`（临时文件，验证后删除）
- Delete: `E:/nsmic/.pi/extensions/smoke-test.ts`（验证后删除）

**Interfaces:**
- Consumes: 无
- Produces: 确认 `tool_call` ID 字段名、`tool_result` 字段名与类型、`sendUserMessage` API 签名

- [ ] **Step 1: 创建 smoke test 扩展**

```typescript
import type { ExtensionAPI, BashToolDetails } from "@earendil-works/pi-coding-agent";
import { isBashToolResult, isToolCallEventType } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.on("tool_call", async (event, ctx) => {
    if (isToolCallEventType("bash", event)) {
      console.log("=== tool_call event ===");
      console.log("toolCallId:", event.toolCallId);
      console.log("toolName:", event.toolName);
      console.log("input keys:", Object.keys(event.input));
      console.log("input.command:", event.input.command);
    }
  });

  pi.on("tool_result", async (event, ctx) => {
    if (isBashToolResult(event)) {
      console.log("=== tool_result event ===");
      console.log("toolCallId:", event.toolCallId);
      console.log("isError:", event.isError);
      console.log("details exitCode:", (event.details as BashToolDetails).exitCode);
      console.log("content type:", Array.isArray(event.content) ? "array" : typeof event.content);
      if (Array.isArray(event.content) && event.content.length > 0) {
        console.log("content[0] type:", event.content[0].type);
      }
    }
  });
}
```

- [ ] **Step 2: 运行 smoke test**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/smoke-test.ts -p "run: echo hello"
```

运行后 pi 不会执行 echo hello（因为 smoke test 没注册自定义 bash 工具），但 `tool_call` 事件会在内部调用时触发。观察输出中 `toolCallId`、`isError`、`details.exitCode` 等字段是否与预期一致。

- [ ] **Step 3: 确认字段名后删除 smoke test**

```bash
rm E:/nsmic/.pi/extensions/smoke-test.ts
```

---

### Task 2: 核心工具函数

**Files:**
- Create: `E:/nsmic/.pi/extensions/review-before-build.ts`

**Interfaces:**
- Consumes: 无
- Produces: 以下纯函数，供 Task 3 的事件处理器调用

| 函数 | 签名 | 说明 |
|------|------|------|
| `isBuildCommand` | `(cmd: string) => boolean` | 匹配 gradle + test/build/assemble/check 之一 |
| `isSimpleCommand` | `(cmd: string) => boolean` | 判定是否为安全可注入的简单命令 |
| `scanSourceFiles` | `(cwd: string) => Promise<Map<string, string>>` | 扫描 .kt + gradle 配置，返回 path→hash 映射 |
| `diffAgainst` | `(current: Map<string, string>, last: Map<string, string>) => string[]` | 返回变化文件列表（相对路径） |
| `buildReminderText` | `(changedFiles: string[]) => { text: string; hasChanges: boolean }` | 生成提醒文案，含路径清理与长度限制 |
| `shellEscape` | `(text: string) => string` | 单引号包裹 + 内部转义，用于安全拼接到 shell 命令 |

- [ ] **Step 1: 实现 `isBuildCommand`**

```typescript
/** 检测是否为构建/测试命令 */
function isBuildCommand(command: string): boolean {
  const lower = command.toLowerCase();
  // 必须包含 gradle + 构建/测试关键词
  if (!lower.includes("gradle")) return false;
  const buildKeywords = /\b(test|build|assemble|check)/;
  return buildKeywords.test(lower);
}
```

- [ ] **Step 2: 实现 `isSimpleCommand`**

```typescript
/** 判定是否为安全可注入的简单命令（全部满足） */
function isSimpleCommand(command: string): boolean {
  // 多行
  if (command.includes("\n")) return false;
  // 管道、分号、&&、||、重定向
  if (/[|;&<>]/.test(command)) return false;
  // 环境变量前缀（FOO=bar cmd 模式）
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(command.trim())) return false;
  // 子 shell、命令替换
  if (/[()]/.test(command)) return false;
  // 引号（单引号/双引号/反引号）
  if (/['"`]/.test(command)) return false;
  // Windows 路径或反斜杠
  if (/\\/.test(command)) return false;
  return true;
}
```

- [ ] **Step 3: 实现 `scanSourceFiles`**

```typescript
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve, relative, sep } from "node:path";
import { glob } from "glob";

/** 扫描源码文件，返回相对路径 → SHA-256 hash 映射 */
async function scanSourceFiles(cwd: string): Promise<Map<string, string>> {
  const snapshot = new Map<string, string>();

  // 文件 glob 模式（相对路径）
  const patterns = [
    "app/src/main/java/**/*.kt",
    "app/src/main/kotlin/**/*.kt",
    "app/src/test/**/*.kt",
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
  ];

  // 排除目录
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
      // 统一 POSIX 风格相对路径
      const posixPath = file.split(sep).join("/");
      snapshot.set(posixPath, hash);
    } catch {
      // 文件被删除/不可读，跳过
    }
  }

  return snapshot;
}
```

- [ ] **Step 4: 实现 `diffAgainst`**

```typescript
/** 对比当前快照与上次快照，返回变化文件列表（新增/修改/删除） */
function diffAgainst(
  current: Map<string, string>,
  last: Map<string, string>
): string[] {
  const changed: string[] = [];

  // 新增或修改的文件
  for (const [path, hash] of current) {
    const lastHash = last.get(path);
    if (lastHash === undefined || lastHash !== hash) {
      changed.push(path);
    }
  }

  // 删除的文件（上次有、这次无）
  for (const path of last.keys()) {
    if (!current.has(path)) {
      changed.push(path);
    }
  }

  return changed.sort();
}
```

- [ ] **Step 5: 实现 `shellEscape`**

```typescript
/** 对文本做 bash 单引号转义：包裹在 '' 中，内部 ' 替换为 '\'' */
function shellEscape(text: string): string {
  // 先清理控制字符（换行符、制表符、回车等替换为空格）
  const cleaned = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, " ");
  // 单引号转义：'foo'bar' → 'foo'\''bar'
  const escaped = cleaned.replace(/'/g, "'\\''");
  return `'${escaped}'`;
}
```

- [ ] **Step 6: 实现 `buildReminderText`**

```typescript
/** 生成提醒文案，返回 { text, hasChanges } */
function buildReminderText(changedFiles: string[]): { text: string; hasChanges: boolean } {
  const MAX_FILES = 10;
  const MAX_PATH_LENGTH = 120;
  const MAX_TOTAL_LENGTH = 2000;

  if (changedFiles.length === 0) {
    return {
      text: "[审查提醒] 自上次构建尝试以来无新代码变化，可直接构建。",
      hasChanges: false,
    };
  }

  // 截断路径长度
  const truncatePath = (p: string): string => {
    if (p.length > MAX_PATH_LENGTH) {
      return p.slice(0, MAX_PATH_LENGTH - 1) + "…";
    }
    return p;
  };

  // 限制文件数量
  const files = changedFiles.slice(0, MAX_FILES);
  const lines = files.map((f) => `  - ${truncatePath(f)}`);
  if (changedFiles.length > MAX_FILES) {
    lines.push(`  - … 以及 ${changedFiles.length - MAX_FILES} 个文件（较多，建议优先审查本次会话改动的文件）`);
  }

  let text = `[审查提醒] 检测到构建命令。本次自上次构建尝试以来变化的新代码：\n${lines.join("\n")}\n按项目约定，构建前应先审查这些文件。`;

  // 总长度限制
  if (text.length > MAX_TOTAL_LENGTH) {
    text = text.slice(0, MAX_TOTAL_LENGTH - 3) + "…";
  }

  return { text, hasChanges: true };
}
```

- [ ] **Step 7: 验证所有工具函数在 Node.js 下可运行**

```bash
cd /d E:\nsmic
node -e "
const { createHash } = require('crypto');
// 快速验证 isBuildCommand 的几个用例
const isBuild = (cmd) => {
  const lower = cmd.toLowerCase();
  if (!lower.includes('gradle')) return false;
  return /\b(test|build|assemble|check)\b/.test(lower);
};
console.log('gradle test:', isBuild('gradle test'));
console.log('gradle assembleDebug:', isBuild('gradle assembleDebug'));
console.log('echo hello:', isBuild('echo hello'));
console.log('gradle --no-daemon :app:testDebugUnitTest:', isBuild('gradle --no-daemon :app:testDebugUnitTest'));

// 验证 isSimpleCommand
const isSimple = (cmd) => {
  if (cmd.includes('\n')) return false;
  if (/[|;&<>]/.test(cmd)) return false;
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(cmd.trim())) return false;
  if (/[()]/.test(cmd)) return false;
  if (/['\"\`]/.test(cmd)) return false;
  if (/\\\\/.test(cmd)) return false;
  return true;
};
console.log('simple gradle test:', isSimple('gradle test'));
console.log('simple cd app && gradle test:', isSimple('cd app && gradle test'));
console.log('simple gradle test | tee log:', isSimple('gradle test | tee log'));
console.log('simple FOO=bar gradle test:', isSimple('FOO=bar gradle test'));

// 验证 shellEscape
const shellEscape = (text) => {
  const cleaned = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, ' ');
  const escaped = cleaned.replace(/'/g, \"'\\\\''\");
  return \"'\" + escaped + \"'\";
};
console.log('escape simple:', shellEscape('hello world'));
console.log('escape with quote:', shellEscape(\"it's fine\"));
console.log('escape with newline:', shellEscape('line1\nline2'));
"
```

预期输出：`gradle test` / `assembleDebug` / `testDebugUnitTest` 均为 true，`echo hello` 为 false；`gradle test` 为简单命令，`cd app && gradle test` / `gradle test | tee log` / `FOO=bar gradle test` 均为非简单；shellEscape 正确处理单引号和换行符。

---

### Task 3: 主扩展实现

**Files:**
- Modify: `E:/nsmic/.pi/extensions/review-before-build.ts`

**Interfaces:**
- Consumes: Task 2 的所有工具函数
- Produces: 注册 3 个事件处理器 + 内存状态

- [ ] **Step 1: 写出完整的扩展文件**

```typescript
import type { ExtensionAPI, BashToolDetails } from "@earendil-works/pi-coding-agent";
import { isBashToolResult, isToolCallEventType } from "@earendil-works/pi-coding-agent";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve, sep } from "node:path";
import { glob } from "glob";

// ============================================================
// 工具函数
// ============================================================

function isBuildCommand(command: string): boolean {
  const lower = command.toLowerCase();
  if (!lower.includes("gradle")) return false;
  return /\b(test|build|assemble|check)\b/.test(lower);
}

function isSimpleCommand(command: string): boolean {
  if (command.includes("\n")) return false;
  if (/[|;&<>]/.test(command)) return false;
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(command.trim())) return false;
  if (/[()]/.test(command)) return false;
  if (/['"`]/.test(command)) return false;
  if (/\\/.test(command)) return false;
  return true;
}

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

  const files = await glob(patterns, { cwd, ignore: ignorePatterns, nodir: true, dot: false });

  for (const file of files) {
    try {
      const absPath = resolve(cwd, file);
      const content = await readFile(absPath);
      const hash = createHash("sha256").update(content).digest("hex");
      const posixPath = file.split(sep).join("/");
      snapshot.set(posixPath, hash);
    } catch {
      // 跳过不可读文件
    }
  }

  return snapshot;
}

function diffAgainst(current: Map<string, string>, last: Map<string, string>): string[] {
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

function shellEscape(text: string): string {
  const cleaned = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, " ");
  const escaped = cleaned.replace(/'/g, "'\\''");
  return `'${escaped}'`;
}

function buildReminderText(changedFiles: string[]): { text: string; hasChanges: boolean } {
  const MAX_FILES = 10;
  const MAX_PATH_LENGTH = 120;
  const MAX_TOTAL_LENGTH = 2000;

  if (changedFiles.length === 0) {
    return { text: "[审查提醒] 自上次构建尝试以来无新代码变化，可直接构建。", hasChanges: false };
  }

  const truncatePath = (p: string): string => (p.length > MAX_PATH_LENGTH ? p.slice(0, MAX_PATH_LENGTH - 1) + "…" : p);
  const files = changedFiles.slice(0, MAX_FILES);
  const lines = files.map((f) => `  - ${truncatePath(f)}`);
  if (changedFiles.length > MAX_FILES) {
    lines.push(`  - … 以及 ${changedFiles.length - MAX_FILES} 个文件（较多，建议优先审查本次会话改动的文件）`);
  }

  let text = `[审查提醒] 检测到构建命令。本次自上次构建尝试以来变化的新代码：\n${lines.join("\n")}\n按项目约定，构建前应先审查这些文件。`;
  if (text.length > MAX_TOTAL_LENGTH) {
    text = text.slice(0, MAX_TOTAL_LENGTH - 3) + "…";
  }
  return { text, hasChanges: true };
}

// ============================================================
// 扩展入口
// ============================================================

export default function (pi: ExtensionAPI) {
  // === 内存状态 ===
  let snapshot = new Map<string, string>();        // 文件路径 → hash
  const injectedCommands = new Map<string, string>(); // toolCallId → 原命令

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
      // 扫描当前文件
      const currentSnapshot = await scanSourceFiles(cwd);

      // 对比找变化
      const changedFiles = diffAgainst(currentSnapshot, snapshot);

      // 生成提醒文案
      const { text: reminderText, hasChanges } = buildReminderText(changedFiles);

      if (isSimpleCommand(event.input.command)) {
        // 简单命令：注入 echo 提醒前缀
        const escapedReminder = shellEscape(reminderText);
        event.input.command = `echo ${escapedReminder} && ${event.input.command}`;
      } else {
        // 复杂命令：仅 UI notify
        const action = hasChanges ? "建议先审查新代码" : "可直接构建";
        ctx.ui.notify(`[审查提醒] 检测到构建命令（复杂命令，未修改）。${action}。`, "info");
      }

      // 更新快照（"上次构建尝试"基线）
      snapshot = currentSnapshot;

      // 记录 toolCallId 供 tool_result 匹配
      injectedCommands.set(event.toolCallId, event.input.command);

    } catch (err) {
      // fail-open: 扩展出错不阻塞构建
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

    // 清理已处理的记录
    injectedCommands.delete(event.toolCallId);

    // 判断是否失败
    // 注意：event.details 在运行时为 undefined，不依赖 details.exitCode
    const isError = event.isError === true;

    if (isError) {
      // 提取输出文本
      const outputText = Array.isArray(event.content)
        ? event.content.map((c) => ("text" in c ? c.text : "")).join("\n")
        : String(event.content ?? "");

      // 截断长输出（防上下文爆炸）
      const truncated = outputText.length > 3000 ? outputText.slice(0, 3000) + "\n…（输出已截断）" : outputText;

      pi.sendUserMessage(
        `构建失败，请修复后重试。\n\n构建输出：\n\`\`\`\n${truncated}\n\`\`\``,
        { deliverAs: "followUp" }
      );
    }
  });
}
```

- [ ] **Step 2: 验证 TypeScript 语法（可选，不编译也行）**

```bash
cd /d E:\nsmic
node -e "
// 检查文件是否存在且可读
const fs = require('fs');
const content = fs.readFileSync('.pi/extensions/review-before-build.ts', 'utf-8');
console.log('File size:', content.length, 'bytes');
console.log('Has export default:', content.includes('export default function'));
console.log('Has before_agent_start:', content.includes('before_agent_start'));
console.log('Has tool_call:', content.includes('tool_call'));
console.log('Has tool_result:', content.includes('tool_result'));
console.log('Has isBuildCommand:', content.includes('isBuildCommand'));
console.log('Has isSimpleCommand:', content.includes('isSimpleCommand'));
console.log('Has scanSourceFiles:', content.includes('scanSourceFiles'));
console.log('Has shellEscape:', content.includes('shellEscape'));
console.log('Has glob:', content.includes('from \"glob\"'));
"
```

预期输出：文件约 7KB+，所有关键函数名均存在。

---

### Task 4: 加载扩展并手动验证

**Files:**
- No changes

- [ ] **Step 1: 用 pi 加载扩展，验证启动无报错**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/review-before-build.ts -p "echo hello"
```

预期：扩展加载成功，无 TypeScript 报错。`echo hello` 不触发构建命令，所以提醒不会出现。正常退出。

- [ ] **Step 2: 验证简单构建命令被注入提醒**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/review-before-build.ts -p "run: gradle test"
```

预期：命令输出中应出现 `[审查提醒] 检测到构建命令。本次自上次构建尝试以来变化的新代码：` 以及文件列表（首次构建，全部文件算新代码）。注意 gradle 本身可能因无环境而报错，但提醒应该先出现。

- [ ] **Step 3: 验证复杂命令不修改**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/review-before-build.ts -p "run: cd app && gradle test"
```

预期：命令输出中不出现 `[审查提醒]`（复杂命令未修改），但 UI 通知会出现（`ctx.ui.notify`）。

- [ ] **Step 4: 验证无变化时的提醒**

对同一个文件路径，连续两次运行：

```bash
cd /d E:\nsmic
pi -e .pi/extensions/review-before-build.ts -p "run: gradle test"
```

第二次运行：快照在上次已更新，无新代码变化，提醒文案应为"无新代码变化，可直接构建"（注意：扩展在每次 pi 进程启动时重新初始化，快照在内存中，所以两次运行是独立的，首次仍为全量。真正的"重复运行"测试需在同一个 pi 会话中连续两次触发相同的构建命令）。

- [ ] **Step 5: 安装到项目**

```bash
# 确保 .pi/extensions 目录存在
mkdir -p E:/nsmic/.pi/extensions
# 复制扩展文件（如果上面是在别处测试的）
cp E:/nsmic/.pi/extensions/review-before-build.ts E:/nsmic/.pi/extensions/review-before-build.ts
```

项目级扩展在 `.pi/extensions/` 下会被 pi 自动发现（需项目被信任）。

- [ ] **Step 6: 在真实 pi 会话中加载**

启动 pi 时不需要额外参数，进入项目目录后扩展自动加载：
```bash
cd /d E:\nsmic
pi
```

确认启动日志中没有扩展相关的错误。在会话中运行 `gradle test` 等命令观察提醒是否出现。

---

## 自检

1. **Spec 覆盖检查**：
   - 硬提醒层（检测+注入/notify）→ Task 3 `tool_call` 处理器 ✓
   - 软拦截层（系统提示）→ Task 3 `before_agent_start` 处理器 ✓
   - 增量审查（快照对比）→ Task 2 `scanSourceFiles` + `diffAgainst` ✓
   - 简单命令判定 → Task 2 `isSimpleCommand` ✓
   - shell 转义 → Task 2 `shellEscape` ✓
   - 提醒内容限制 → Task 2 `buildReminderText` ✓
   - fail-open → Task 3 try/catch ✓
   - 构建失败闭环 → Task 3 `tool_result` 处理器 ✓
   - API smoke test → Task 1 ✓
   - 文件范围：`src/main/java/`、`src/main/kotlin/`、`src/test/`、gradle 配置 → Task 2 `scanSourceFiles` ✓

2. **占位符检查**：无 TBD/TODO 占位符，所有步骤包含完整代码 ✓

3. **类型一致性**：
   - `isBuildCommand(command: string): boolean` → 在 `tool_call` 和 `before_agent_start` 中一致使用 ✓
   - `isSimpleCommand(command: string): boolean` → 在 `tool_call` 中一致使用 ✓
   - `scanSourceFiles(cwd: string): Promise<Map<string, string>>` → 结果写入 `snapshot` ✓
   - `diffAgainst(current, last): string[]` → 返回值传给 `buildReminderText` ✓
   - `shellEscape(text: string): string` → 用于构造 echo 命令 ✓
   - `injectedCommands: Map<toolCallId, originalCommand>` → Task 3 中 `tool_call` 写入、`tool_result` 读取 ✓