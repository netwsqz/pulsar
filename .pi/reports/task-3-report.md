# Task 3 Report: 主扩展实现 — 事件处理器

## 实现内容

### 1. 新增 imports（文件顶部）

在现有 `import { glob } from "glob";` 之后新增了两行：

```typescript
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { isBashToolResult, isToolCallEventType } from "@earendil-works/pi-coding-agent";
```

现有 6 个工具函数所需的 `createHash`、`readFile`、`resolve`、`sep`、`glob` 等 imports 保持不变。

### 2. 新增 `export default function` 事件处理器（文件末尾）

追加了 `export default function (pi: ExtensionAPI)` 块，包含：

- **内存状态**：`snapshot`（`Map<string, string>` 文件快照）和 `injectedCommands`（`Map<toolCallId, command>` 跟踪已注入命令的 tool call）

- **`before_agent_start` 事件**（软拦截层）：检测 prompt 是否包含构建命令，若是则向 `systemPrompt` 追加代码审查约定提示文本。

- **`tool_call` 事件**（硬提醒层）：
  - 过滤非 bash 或非构建命令的 tool call
  - 扫描源代码文件，与上次快照比较差异
  - 简单命令：用 `echo` 注入提醒文本，形成 `echo '提醒' && 原命令`
  - 复杂命令：通过 `ctx.ui.notify` 发送提醒通知
  - 更新快照，记录 toolCallId

- **`tool_result` 事件**（构建失败反馈闭环）：
  - 过滤非 bash 或未被跟踪的 tool call
  - 仅依赖 `event.isError`（API smoke test 发现 `details` 运行时为 `undefined`）
  - 截取输出前 3000 字符，通过 `pi.sendUserMessage` 发送构建失败消息

### 3. 遵循 API 约束

- 不使用 `event.details.exitCode`（运行时为 `undefined`）
- 使用 `event.isError` 判断构建失败
- `event.content` 按 `{ type: "text", text: string }[]` 处理

## 验证结果

| 检查项 | 结果 |
|--------|------|
| 文件大小 | 7774 bytes |
| 包含 `export default function` | ✅ |
| 包含 `before_agent_start` | ✅ |
| 包含 `tool_call` | ✅ |
| 包含 `tool_result` | ✅ |
| 包含 `isBuildCommand` | ✅ |
| 包含 `isSimpleCommand` | ✅ |
| 包含 `scanSourceFiles` | ✅ |
| 包含 `shellEscape` | ✅ |
| 包含 `glob` import | ✅ |
| 包含 `isBashToolResult` | ✅ |
| 包含 `isToolCallEventType` | ✅ |
| pi 加载扩展启动无报错 | ✅（`pi -e .pi/extensions/review-before-build.ts -p "echo hello"` 输出 `hello`） |

## 文件变更

- `E:/nsmic/.pi/extensions/review-before-build.ts` — 新增 2 个 imports + 追加 `export default function` 事件处理器块

## 自我审查发现

1. **类型安全**：`ExtensionAPI` 使用 `import type` 导入，正确用于 default export 的参数类型标注。
2. **错误处理**：`tool_call` 事件处理器有 try-catch 包裹，异常时通过 `ctx.ui.notify` 报告。
3. **资源清理**：`tool_result` 事件处理器在检查后立即 `delete` 命令记录，防止内存泄漏。
4. **API 兼容性**：已按 smoke test 发现调整，避免访问 `undefined` 的 `details` 属性。
5. **无外部依赖**：事件处理器逻辑全部使用标准 API 和已有工具函数。

## 问题与担忧

- 当前项目目录下可能没有 `.kt` 或 Gradle 配置文件，`scanSourceFiles` 会返回空 Map，首次构建提醒会正确显示"无新代码变化"。
- 未进行运行时集成测试（实际触发 `before_agent_start`/`tool_call`/`tool_result` 事件），因为需要完整的 pi 运行环境。但语法验证和加载验证已通过。