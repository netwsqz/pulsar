# Task 2: 核心工具函数

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

---

**Step 1: 实现 `isBuildCommand`**

```typescript
function isBuildCommand(command: string): boolean {
  const lower = command.toLowerCase();
  if (!lower.includes("gradle")) return false;
  const buildKeywords = /\b(test|build|assemble|check)\b/;
  return buildKeywords.test(lower);
}
```

**Step 2: 实现 `isSimpleCommand`**

```typescript
function isSimpleCommand(command: string): boolean {
  if (command.includes("\n")) return false;
  if (/[|;&<>]/.test(command)) return false;
  if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(command.trim())) return false;
  if (/[()]/.test(command)) return false;
  if (/['"`]/.test(command)) return false;
  if (/\\/.test(command)) return false;
  return true;
}
```

**Step 3: 实现 `scanSourceFiles`**

```typescript
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve, sep } from "node:path";
import { glob } from "glob";

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
```

**Step 4: 实现 `diffAgainst`**

```typescript
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
```

**Step 5: 实现 `shellEscape`**

```typescript
function shellEscape(text: string): string {
  const cleaned = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, " ");
  const escaped = cleaned.replace(/'/g, "'\\''");
  return `'${escaped}'`;
}
```

**Step 6: 实现 `buildReminderText`**

```typescript
function buildReminderText(changedFiles: string[]): { text: string; hasChanges: boolean } {
  const MAX_FILES = 10;
  const MAX_PATH_LENGTH = 120;
  const MAX_TOTAL_LENGTH = 2000;

  if (changedFiles.length === 0) {
    return { text: "[审查提醒] 自上次构建尝试以来无新代码变化，可直接构建。", hasChanges: false };
  }

  const truncatePath = (p: string): string =>
    p.length > MAX_PATH_LENGTH ? p.slice(0, MAX_PATH_LENGTH - 1) + "…" : p;

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
```

**Step 7: 验证所有工具函数**

```bash
cd /d E:\nsmic
node -e "
const isBuild = (cmd) => {
  const lower = cmd.toLowerCase();
  if (!lower.includes('gradle')) return false;
  return /\b(test|build|assemble|check)\b/.test(lower);
};
console.log('gradle test:', isBuild('gradle test'));
console.log('gradle assembleDebug:', isBuild('gradle assembleDebug'));
console.log('echo hello:', isBuild('echo hello'));
console.log('gradle --no-daemon :app:testDebugUnitTest:', isBuild('gradle --no-daemon :app:testDebugUnitTest'));
console.log('gradle --no-daemon :app:assembleDebug:', isBuild('gradle --no-daemon :app:assembleDebug'));

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

预期输出：
- `gradle test` → true, `gradle assembleDebug` → true, `echo hello` → false, `testDebugUnitTest` → true, `assembleDebug` → true
- `gradle test` → true (simple), `cd app && gradle test` → false, `gradle test | tee log` → false, `FOO=bar gradle test` → false
- shellEscape handles single quotes and control chars correctly