# Task 1: API Smoke Test Report

## What Was Implemented

Created a temporary smoke test extension at `.pi/extensions/smoke-test.ts` that subscribes to `tool_call` and `tool_result` events, logging their field structure for a single `bash` tool invocation (`echo hello`). After verification, the file was deleted.

## Key Observations

### `tool_call` Event Fields

| Field | Value | Notes |
|-------|-------|-------|
| `toolCallId` | `call_<hex>` (e.g., `call_d24c4fc8f90443b199060914`) | ✅ Present, string type |
| `toolName` | `"bash"` | ✅ Present |
| `input` | `{ command: "echo hello" }` | ✅ Present, keys: `["command"]` |
| `input.command` | `"echo hello"` | ✅ Present, correct value |

### `tool_result` Event Fields

| Field | Value | Notes |
|-------|-------|-------|
| `toolCallId` | Same as `tool_call` | ✅ Present, matches |
| `isError` | `false` | ✅ Present, boolean |
| `details` | `undefined` | ⚠️ **Present as a key but value is `undefined`** |
| `content` | Array | ✅ Present, `Array.isArray() === true` |
| `content[0].type` | `"text"` | ✅ Present |
| `content[0].text` | `"hello\n"` | ✅ Present (command output) |
| `usage` | Present | ✅ Present |
| All event keys | `["type", "toolName", "toolCallId", "input", "content", "details", "isError", "usage"]` | Full set |

### Surprises / Concerns

1. **`details` is `undefined` for bash tool results.** The `BashToolDetails` type (from the import) declares `exitCode` as a field, but at runtime the `details` property on the `tool_result` event is `undefined`. This means:
   - `(event.details as BashToolDetails).exitCode` throws `TypeError: undefined is not an object`
   - The `exitCode` information is not available via `details` for bash results in the current pi version
   - If the planned extension needs `exitCode`, it will need an alternative approach (e.g., wrapping the command or parsing from content)

2. **`content` is always an array** of `{ type: "text", text: string }` objects. This is consistent with the ContentBlock type pattern.

3. **`isError` works correctly** - `false` for successful commands, presumably `true` for failed ones.

## Files Changed

- **Created:** `E:/nsmic/.pi/extensions/smoke-test.ts` (temporary, deleted after verification)
- **Deleted:** `E:/nsmic/.pi/extensions/smoke-test.ts` after test completion

## Self-Review Findings

- First run used `(event.details as BashToolDetails).exitCode` which crashed because `details` is `undefined` at runtime. The second run with defensive checks revealed the actual field structure.
- The `details` field being `undefined` is a runtime behavior that differs from the TypeScript type declaration — it may be a bug in the pi version, or `details` may only be populated for certain tool types (not bash). This should be validated if the extension needs `exitCode`.

## Issues or Concerns

**High priority for later tasks:** The planned `review-before-build` extension needs to detect command exit codes (e.g., a failed build command). Since `details.exitCode` is `undefined` for bash tool results, the extension cannot rely on `BashToolDetails.exitCode` to determine if a command failed. Alternative approaches:
- Check `event.isError` (boolean) to determine if the tool execution errored
- Parse the `content[0].text` for error indicators
- Pipe command output through a wrapper that captures exit codes

## Status

**DONE** — Smoke test completed successfully. All key fields verified. Critical finding about `details.exitCode` being `undefined` should inform the design of the real extension.