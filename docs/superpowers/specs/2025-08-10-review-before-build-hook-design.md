# 构建前自动审查新代码 — 设计文档

## 概述

在 pi 中增加一个钩子（hook）：**每次运行构建/测试命令时，自动提醒 LLM 审查"新代码"**（自上次构建尝试以来发生变化的文件）。采用 hookify 式的"硬提醒 + 软决定"机制：**硬提醒层**负责检测与注入提醒，最终是否审查由 LLM 依据系统提示（软拦截）决定，**不阻塞、不阻止构建**。

## 技术选型

pi 扩展（TypeScript，`.pi/extensions/`），复刻 hookify `warn` 动作的机制：

- 项目主力工具是 pi，扩展是其原生机制，事件 API 完整
- 项目不是 git 仓库，git hooks 不可用
- hookify 规则（`.Codex/`）是 Codex 生态，pi 不加载；在 pi 扩展中复刻其"检测 → 注入提醒 → LLM 决定"的形态

## 核心机制：硬提醒 + 软决定

> 命名说明：不使用"硬拦截"——本机制**不阻止构建**，只修改命令注入提醒。故称"硬提醒层"。

| 层 | 机制 | 职责 |
|----|------|------|
| 硬提醒层（检测与注入） | `tool_call` 检测构建命令 → 修改命令前缀注入 echo 提醒 | 简单命令：提醒到达 LLM（命令输出）；复杂命令：UI 通知提醒用户，**不保证进入 LLM 上下文** |
| 软拦截层（决定） | `before_agent_start` 注入系统提示 | LLM 依据提示决定是否审查、如何审查 |

### 硬提醒层流程

```
LLM 调用构建命令（bash: gradle test ...）
  │
  ├─ try/catch 包裹（fail-open，见"容错"）
  ├─ 对比快照 → 找出新代码文件
  ├─ 判断命令是否为"简单命令"（见下节安全限制）
  │
  ├─ 简单命令 ──→ 修改 event.input.command，前缀注入 echo 提醒：
  │   有变化：echo "[审查提醒] 检测到构建命令。
  │             本次自上次构建尝试以来变化的新代码：
  │             - <相对路径1>
  │             - <相对路径2>
  │             按项目约定，构建前应先审查这些文件。"
  │   无变化：echo "[审查提醒] 自上次构建尝试以来无新代码变化，可直接构建。"
  │           && <原构建命令>
  │
  ├─ 复杂命令 ──→ 不修改原命令；`ctx.ui.notify()` 提醒用户"检测到构建命令，
  │                 建议先审查新代码：<文件列表>"
  │                 ⚠️ UI 通知不保证进入 LLM 上下文，仅提醒用户本人
  │
  └─ 无论是否注入，记录快照基线（见"快照更新时机"）
```

**提醒到达层的说明（不承诺"保证到达 LLM"）：**

- 简单命令：提醒写入命令输出，LLM 能看到 → 到达 LLM ✓
- 复杂命令：仅 `ctx.ui.notify()` → 到达用户（UI），是否进入 LLM 上下文取决于 Pi 事件机制，**不保证**
- MVP 不采用 `pi.sendMessage()` 注入复杂命令的提醒——会引入消息重入与时序问题，后续需要再加

### 软拦截层（系统提示注入）

`before_agent_start` 中检测用户输入是否含构建/测试意图，若是则向系统提示注入：

> 运行构建/测试命令前，应先审查自上次构建尝试以来变化的代码文件；审查通过后再执行构建。

## 触发规则

### 构建/测试命令识别

拦截 `bash` 工具调用，命令匹配以下模式之一即视为构建/测试：

- 包含 `gradle` 且包含 `test` / `build` / `assemble` / `check` 之一（如 `gradle testDebugUnitTest`、`gradle assembleDebug`）
- `--no-daemon` 等参数不影响识别

### 安全限制：仅修改简单命令

**只对"简单命令"注入 echo 前缀；复杂命令只 notify、不修改原始命令。**

简单命令判定（全部满足）：
- 单行、无换行
- 无管道 `|`、分号 `;`、`&&`、`||`、重定向 `>` / `<`
- 无环境变量前缀（如 `FOO=bar gradle test`）
- 无子 shell `(...)` / 命令替换 `$(...)` / 引号包裹
- 无 Windows 路径或反斜杠

复杂命令（如 `cd app && gradle test`、`gradle test | tee log`、`FOO=bar gradle test`）→ 不修改，仅 `ctx.ui.notify()` 提示。

**理由**：把提醒文本拼进任意 shell 命令可能改变命令语义（管道/短路/引号/环境变量前缀/子 shell 等），风险不可接受。

> ⚠️ **Windows 环境下的实际效果**：本项目的 Gradle 用法是 `gradle.bat --no-daemon :app:testDebugUnitTest`——若命令中出现 Windows 路径或反斜杠，将被判为复杂命令，**只提醒、不修改**。
> 即：**当前注入逻辑只适用于 POSIX 风格简单命令；Windows 路径命令只提醒，不修改原命令**。
> 手动验证场景 1/2/3 需在无 Windows 路径的简单命令下进行。

**echo 文本必须做完整 shell 转义**：提醒文本中所有特殊字符（引号 `"`、反引号、`$`、反斜杠、控制字符）在拼接进命令前必须转义（如 shell-escape 函数处理），**不能只做控制字符清理**。

## 增量审查机制（只提醒新代码）

扩展在内存中维护**文件快照**（`文件路径 → 内容 SHA-256`），基于 Node 内置 `node:crypto` 计算，无额外依赖。

### 文件范围（哪些算"代码"）

- `app/src/main/java/**/*.kt`（主代码）
- `app/src/main/kotlin/**/*.kt`（主代码，备用目录）
- `app/src/test/**/*.kt`（测试代码，TDD 项目测试同样重要）
- `app/build.gradle.kts`、`build.gradle.kts`、`settings.gradle.kts`（构建配置）
- 排除 `build/`、`.gradle/` 等生成目录

> 注：当前版本面向**单模块 Android 项目**（app/ 模块）。多模块项目（library 等）后续扩展。

### 快照更新时机

**在 `tool_call` 识别到构建命令时立即更新快照**（发生在命令执行前）。语义为"**上次构建尝试**"的基线，而非"上次成功构建"。

> 构建命令在 `tool_call` 阶段被识别即视为一次构建尝试，**不论后续命令成功、失败或被用户取消，快照都已更新**。

- 构建失败也算一次基线更新（简单方案，无状态机）
- 构建过程中代码被修改：下一次构建对比的是"上次构建尝试时的状态"，只看到其后变化
- 扩展异常但构建仍执行：快照可能已更新（fail-open 的已知取舍，可接受）

### 首次构建与审查粒度

- 首次构建：快照为空 → 全部文件算"新代码"，提醒全量审查
- 审查粒度：**文件级**（提醒列出变化文件路径），不做行级 diff
- 快照存**内存**，会话重启后失效 → 下次构建重新全量提醒，不漏

### 提醒内容限制（防上下文膨胀）

- 文件数上限 **10 个**，超出时提示"其余文件较多，建议优先审查本次会话改动的文件"
- 单个路径限制：长度 ≤ 120 字符，超出截断并加 `…`
- 提醒文本总长度上限（约 2000 字符），超出截断
- 路径统一为**相对 cwd 的 POSIX 风格**（`/` 分隔），Windows 反斜杠替换为 `/`
- 路径中的换行符、控制字符（`\n`、`\r`、`\t` 等）清理或替换为空格

## 容错 fail-open

扩展自身出错（hash 计算失败、文件扫描异常等）不得影响构建：

- 整个检测/注入逻辑包 try/catch
- 出错时**不修改命令**（构建照常执行）+ `ctx.ui.notify()` 提示"审查提醒钩子异常，已跳过"
- 绝不因扩展 bug 卡死开发流程

## 构建失败反馈闭环

监听 `tool_result` 事件，**只处理被本扩展注入过的命令**（用 `tool_call` 阶段记录的 `toolCallId → 原命令` 映射区分）：

- 成功/失败判断使用真实字段，**不从输出文本正则猜测**。已确认当前 pi 版本（0.84.1）类型：
  - `tool_call` / `tool_result` / `tool_execution_end` 的 ID 字段均为 **`event.toolCallId`**（命名一致）
  - `tool_result` 事件含 **`event.isError`**（boolean，正常工作）
  - **`event.details` 在运行时为 `undefined`**（`BashToolDetails` 类型存在但运行时值未定义），因此**不使用 `details.exitCode`**，仅依赖 `event.isError`
  - `event.content` 为内容数组（`{ type: "text", text: string }`）
  - follow-up 消息 API：`pi.sendUserMessage(content, { deliverAs: "followUp" })`（`sendMessage` 的 `deliverAs` 同样支持 `"steer"` / `"followUp"` / `"nextTurn"`）
- 用 `toolCallId → 原命令` 映射只处理被注入过的命令（`injectedCommands`）
- 判断失败 → 排队 follow-up 消息："构建失败，请修复后重试"，附 `event.content`（实际输出）作为错误上下文
- 与"构建前提醒"形成完整闭环：提醒审查 → 构建 → 失败修复 → 再构建

> 注意：注入的 `echo ... && <原命令>` 中，echo 成功即执行原命令，最终 exitCode 即原命令的 exitCode，不会被注入前缀污染。复杂命令未被注入，其失败不触发本闭环（仅简单注入命令触发）。

## 改动范围

新增单文件：`.pi/extensions/review-before-build.ts`（项目级扩展，自动发现，需项目被信任后加载）。

### 模块划分（单文件内分节）

| 节 | 职责 |
|----|------|
| 命令识别 | `isBuildCommand(command)`：正则匹配 |
| 简单命令判定 | `isSimpleCommand(command)`：安全限制检查 |
| 文件扫描 | `scanSourceFiles(cwd)`：递归 .kt + gradle 配置，排除生成目录 |
| 快照管理 | `computeSnapshot()` / `diffAgainst(last)`：返回变化文件列表 |
| 提醒注入 | `buildReminderText(files)`：生成文案（含路径清理、**完整 shell 转义**、长度限制） |
| 硬提醒层 | `tool_call` 处理器：识别 → 注入/notify → 记录快照与 toolCallId |
| 软拦截层 | `before_agent_start` 处理器：注入系统提示 |
| 构建失败 | `tool_result` 处理器：按 toolCallId 匹配注入过的命令，失败排队修复消息 |

### 实现前 API smoke test（必做）

1. 读取 `@earendil-works/pi-coding-agent` 的 `dist/index.d.ts`，确认：
   - `tool_call` / `tool_result` 的 ID 字段名（预期 `toolCallId`）
   - `event.isError`、`event.details`、`event.content` 类型
   - `pi.sendUserMessage` 的 `deliverAs` 选项
2. 用一个最小扩展（`pi -e ./smoke.ts`）打印上述字段，验证与文档一致
3. 确认 `event.details` 在运行时实际可用（当前版本 0.84.1 中 `details` 为 `undefined`，因此仅依赖 `event.isError`）
4. 确认后按本文档实现

### 事件订阅清单

| 事件 | 用途 |
|------|------|
| `before_agent_start` | 软拦截层：注入审查提示 |
| `tool_call` | 硬提醒层：识别构建命令，注入/notify，记录快照 |
| `tool_result` | 构建失败反馈闭环 |

### 内存状态

- `snapshot: Map<path, hash>` — 文件快照（"上次构建尝试"基线）
- `injectedCommands: Map<toolCallId, originalCommand>` — 被注入过的命令，供 tool_result 匹配

## 测试要点

- 无 Android/Kotlin 代码改动，现有单元测试不受影响
- 手动验证场景：
  1. 修改一个 .kt 文件 → 运行 `gradle test`（简单命令）→ 命令输出中应出现审查提醒，含该文件路径
  2. 不修改文件直接重跑同一命令 → 提醒仍注入，文案为"无新代码变化，可直接构建"
  3. 构建命令加 `--no-daemon` 参数 → 仍能识别并注入
  4. 复杂命令 `cd app && gradle test` → 原命令不被修改，仅 notify
  5. 首次构建 → 提醒全部文件为"新代码"
  6. 构建失败（简单命令）→ 应排队修复消息；失败后重跑 → 快照已更新（"上次构建尝试"语义），不再提醒已存在的文件
  7. 扩展异常（如删除源码目录后构建）→ 构建照常执行，不卡死

## 不做的事（YAGNI）

- 阻塞/阻止构建（硬决定）——本次明确放弃，采用软决定
- 复杂命令的提醒注入（`pi.sendMessage` 注入到 LLM）——避免消息重入与时序问题，仅 UI notify
- "上次成功构建"基线（需暂存扫描结果到 tool_result）——采用简单方案"上次构建尝试"
- 审查报告落盘（`.pi/reviews/`）——后续需要再加
- 触发关键词/文件范围/提醒 agent 可配置化——先硬编码
- 行级 diff、依赖分析（审查"受影响的调用方"）——过度设计
- `review_complete` 状态机、防循环计数——快照在 tool_call 时自动更新，无需状态机
- 多模块 Android 项目支持——当前仅单模块（app/）
