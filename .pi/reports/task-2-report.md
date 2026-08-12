# Task 2 Report: 核心工具函数

## 实现内容

创建了 `E:/nsmic/.pi/extensions/review-before-build.ts`，包含 6 个纯工具函数，无事件处理器：

1. **`isBuildCommand(command)`** — 检测 Gradle 构建/测试命令
2. **`isSimpleCommand(command)`** — 判定是否为安全可注入的简单命令
3. **`scanSourceFiles(cwd)`** — 扫描 .kt + gradle 配置，返回 path→SHA-256 映射
4. **`diffAgainst(current, last)`** — 比较两个快照，返回变化文件列表
5. **`shellEscape(text)`** — 单引号包裹 + 内部转义，用于安全拼接到 shell 命令
6. **`buildReminderText(changedFiles)`** — 生成提醒文案，含路径清理与长度限制

## 验证结果

### isBuildCommand
| 输入 | 预期 | 实际 |
|------|------|------|
| `gradle test` | true | true |
| `gradle assembleDebug` | true | true |
| `echo hello` | false | false |
| `gradle --no-daemon :app:testDebugUnitTest` | true | true |
| `gradle --no-daemon :app:assembleDebug` | true | true |
| `gradle clean` | false | false |
| `check` | false | false |

### isSimpleCommand
| 输入 | 预期 | 实际 |
|------|------|------|
| `gradle test` | true | true |
| `cd app && gradle test` | false | false |
| `gradle test \| tee log` | false | false |
| `FOO=bar gradle test` | false | false |
| `gradle (test)` | false | false |
| `gradle\ntest` | false | false |
| `gradle 'test'` | false | false |
| `gradle test\\` | false | false |

### shellEscape
- `hello world` → `'hello world'` ✓
- `it's fine` → `'it'\''s fine'` ✓
- `line1\nline2` → wrapped in single quotes ✓
- `hello\x00world` → control char replaced with space ✓

### scanSourceFiles
- 发现 **71 个文件**（.kt + gradle 配置）
- 快照大小: 71 条目
- 所有文件可读，无跳过的文件

### diffAgainst
- 对比空快照 → 71 个文件全部标记为变化 ✓
- 返回排序结果 ✓

### buildReminderText
- 空数组返回 `{ text, hasChanges: false }` ✓
- 单文件生成正确格式 ✓
- 15 个文件正确截断到 10 个 + 额外提示 ✓

## 文件变更

- `E:/nsmic/.pi/extensions/review-before-build.ts` — 新建（6 个工具函数，无事件处理器）
- `E:/nsmic/.pi/task-2-verify.js` — 临时验证脚本（可删除）

## 自审发现

1. **`isBuildCommand` 正则修正**：原正则 `/\b(test|build|assemble|check)\b/` 在 `assembleDebug` 中匹配失败，因为 `assemble` 后面没有单词边界。修正为 `/\b(test|build|assemble|check)/` 去掉末尾 `\b`，使 `assembleDebug` 和 `testDebugUnitTest` 正确匹配。

2. **glob 依赖**：`glob` 包不在系统全局 node_modules 中，需要在项目目录安装。已在 `E:/nsmic/` 下 `npm install glob` 安装。但 pi 扩展通过 jiti 加载，会解析项目目录的 node_modules，不影响扩展运行。

3. **`scanSourceFiles` 模式**：`app/src/main/kotlin/**/*.kt` 模式在当前项目中不匹配任何文件（项目使用 java 目录），但这是无害的，glob 对空匹配返回空数组。

## 后续注意事项

- 该文件作为模块导出，供 Task 3 的事件处理器 `import { isBuildCommand, ... } from "./review-before-build"` 使用
- 部署时确保 `glob` 包在扩展的 `package.json` 依赖中或项目 node_modules 中