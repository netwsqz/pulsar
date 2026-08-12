# Task 1: API smoke test — 验证 Pi 事件类型

**Files:**
- Create: `E:/nsmic/.pi/extensions/smoke-test.ts`（临时文件，验证后删除）
- Delete: `E:/nsmic/.pi/extensions/smoke-test.ts`（验证后删除）

**Interfaces:**
- Consumes: 无
- Produces: 确认 `tool_call` ID 字段名、`tool_result` 字段名与类型、`sendUserMessage` API 签名

---

**Step 1: 创建 smoke test 扩展**

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

**Step 2: 运行 smoke test**

```bash
cd /d E:\nsmic
pi -e .pi/extensions/smoke-test.ts -p "run: echo hello"
```

**Step 3: 确认字段名后删除 smoke test**

```bash
rm E:/nsmic/.pi/extensions/smoke-test.ts
```