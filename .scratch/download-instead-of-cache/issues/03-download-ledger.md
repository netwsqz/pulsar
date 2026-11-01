# 03 — 下载名单（DownloadLedger）

**What to build:** 记住「这张专辑/这个列表当初下了哪几首」。有了它，删除只删当初那批，服务器后来新增的歌不会被连带删掉。

**Blocked by:** None — can start immediately.

**Status:** ready-for-human（代码完成）

- [x] `DownloadLedger` 数据模型（`serverUrl` / `userId` / `kind` / `id` / `name` / `trackIds` / `createdAt`），JSON 序列化
- [x] `DownloadLedgerStore` 接口 + DataStore 实现（照 `SessionRepository`/`SessionStore` 那套）
- [x] 读写都是整表覆写（数据量小，别做增量）
- [x] 按 `(serverUrl, userId)` 过滤的查询方法——换账号看不见别人的名单（用户故事 17）
- [x] **退出登录不清名单**（用户故事 16）——这条没有代码要写，但要在现有 `signOut` 里**确认它没被顺手清掉**，并加测试钉死
- [x] 纯函数单测：序列化/反序列化往返、按账号过滤、同名不同 kind 不冲突

## Comments

### 实现记录（2026-10-25，agent）

**结论：代码完成，13 个单测全绿，全量 617 个单测无一失败。**

**新建文件**

- `app/src/main/java/com/example/pulsar/DownloadLedger.kt`
  - `DownloadEntry` / `DownloadKind` / `DownloadLedger`：`@Serializable` 数据模型。`kind` 用枚举 + `@SerialName("album"|"playlist"|"orphan")`，落盘字符串与规格一致。
  - `DownloadLedgerCodec`：纯函数编解码，照 `LibrarySnapshotCodec` 的写法。`decode` 对坏数据返回 `null`，不抛。
  - `DownloadLedgerStore` 接口：`load` / `replaceAll`（整表覆写）/ `entriesFor(serverUrl, userId)` / `migrationDone` / `markMigrationDone`。
  - `DataStoreDownloadLedgerStore`：整张名单序列化成一段 JSON 存在一个 `stringPreferencesKey("download_ledger_json")` 里，`migrationDone` 单独一个 boolean key（`download_ledger_migration_done`）。
- `app/src/test/java/com/example/pulsar/DownloadLedgerTest.kt`：13 个测试（往返 / 坏 JSON / 未知字段兼容 / 同名不同 kind / 落盘字段名与 kind 取值 / 整表覆写 / 三种按账号过滤 / 退出登录不清名单 / migrationDone 持久化 / 落盘坏了降级空名单）。

**一处实现上的取舍（请 reviewer 留意）**

`guardedIo` 的 block 是非 suspend 的（`() -> T`），而 DataStore 的 `data.first()` / `edit {}` 都是 suspend，塞不进去。所以在本文件里写了个 `guardedSuspendIo`（`suspend () -> T` 版，兜底口径与 `guardedIo` 完全一致：IO 线程、吞异常、取消上抛）。**没有改 `MediaCache.kt`**——那个文件归别的工单。若后续有第三个文件也要用，再考虑把 suspend 版提到公共位置。

**关于「退出登录不清名单」**

现有 `JellyfinViewModel.signOut()` 只调 `sessionStore.clear()` + `clearLocalCopyAndSnapshot()`，不碰任何名单——**没有顺手清掉，符合预期**，因此 `JellyfinViewModel.kt` 一行未改。这条靠 `DownloadLedgerTest.「退出登录（清会话）不清理下载名单」` 钉死：同一份 DataStore 上先写名单再 `SessionStore.clear()`，断言名单仍在。

**未做（属于别的工单）**

- `PulsarApplication` 里挂 `downloadLedgerStore()` 工厂——留给接线工单（改了会越界）。
- 存量迁移的实际反查逻辑（工单 07），本票只提供 `migrationDone` 标记。

