# Task 3: 主扩展实现 — 事件处理器

**Files:**
- Modify: `E:/nsmic/.pi/extensions/review-before-build.ts`

**Interfaces:**
- Consumes: Task 2 的 6 个工具函数（已在文件中，已 export）
- Produces: 注册 3 个事件处理器 + 内存状态

---

## 现有文件状态

`E:/nsmic/.pi/extensions/review-before-build.ts` 已包含以下 export 函数：

```typescript
export function isBuildCommand(command: string): boolean;
export function isSimpleCommand(command: string): boolean;
export function scanSourceFiles(cwd: string): Promise<Map<string, string>>;
export function diffAgainst(current: Map<string, string>, last: Map<string, string>): string[];
export function shellEscape(text: string): string;
export function buildReminderText(changedFiles: string[]): { text: string; hasChanges: boolean };
```

## 关键实现约束

**来自 API smoke test 的发现：**
- `event.details` 在运行时为 `undefined`（`BashToolDetails` 类型存在但运行时值未定义）
- 因此**不使用 `details.exitCode`**，仅依赖 `event.isError` 判断构建失败
- `event.content` 是 `{ type: "text", text: string }[]` 数组

**项目不是 git 仓库**，不要用 git 相关命令。

---

**Step 1: 在文件末尾追加 `export default function (pi: ExtensionAPI)`**

追加以下内容到现有文件末尾（不要删除任何现有代码）：

```typescript
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { isBashToolResult, isToolCallEventType } from "@earendil-works/pi-coding-agent";

// 注意：这些函数已在文件上方定义，import 语句只在文件顶部使用
// 但此文件是单文件扩展，所有代码在同一文件中，函数已在全局作用域可用

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
```

**Step 2: 验证 TypeScript 语法**

```bash
cd /d E:\nsmic
node -e "
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
console.log('Has isBashToolResult:', content.includes('isBashToolResult'));
console.log('Has isToolCallEventType:', content.includes('isToolCallEventType'));
"
```

**Step 3: 用 pi 加载扩展验证启动无报错**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/review-before-build.ts -p "echo hello"
```