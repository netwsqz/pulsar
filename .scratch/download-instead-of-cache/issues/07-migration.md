# 07 — 迁移：存量副本反推成下载名单

**What to build:** 升级之后，老版本自动攒下的副本直接变成「下载」——一首不丢，离线能力不归零，设置页里看得见。

**Blocked by:** 03 — 下载名单

**Status:** 已撤销（2026-10-25）

> **这一票不做了。** 迁移在真机上跑出来一批 `serverUrl` 为空的坏条目（离线启动走 `goOnline()`
> 时 `_state.value.serverUrl` 还是空串），用户决定直接清掉老副本、从零开始，不修迁移。
> 代码已整体删除（`buildMigratedEntries`、`migrationDone`、`DownloadKind.ORPHAN` 及相关测试）。

- [x] 首次启动跑一次迁移，`DownloadLedgerStore` 里存 `migrationDone` 标记，只跑一次
- [x] 拿 `completeKeys()` 全量，**遍历当前曲目表逐个算 `cacheKey(track)` 去比对**（`O(n)`，几百首无压力）。
      **不要**写「从 URL 反解 trackId」的解析器——现有 `JellyfinUrlBuilder` 只有构造方向，反解容易漂
- [x] 按专辑归组写名单（`createdAt` = 迁移时间）
- [x] 反查不到的 → `kind = "orphan"` 单独一组，界面上叫「其他」，可逐首删（用户故事 18）
- [x] 迁移失败不重试、不留半截：写不进去就当没有旧副本，**打日志**但不报错（铁律 4 的同类）
- [x] 曲目表还没加载时别跑（恢复会话中）——迁移挂在「登录完成 / 曲库就绪」之后
- [x] 测试：迁移只跑一次；反查不到的进 orphan；空缓存不写空名单

## Comments

### 实现记录（2026-10-25，agent）

**结论：代码完成，全量 640 个单测通过（新增 11 个）。**

**改动的文件**

- `app/src/main/java/com/example/pulsar/DownloadLedger.kt`（只追加）
  - `buildMigratedEntries(tracks, keys, cacheKey, serverUrl, userId, now): List<DownloadEntry>` —— 反推的纯函数。**不做 URL 反解**：遍历曲目表、逐首算 `cacheKey` 去跟 `completeKeys()` 比对。命中的按专辑名归组成 `kind = ALBUM`（`id` 也用专辑名——`Track` 模型里没有专辑 id，这跟现有离线专辑兜底的口径一致）；没被任何曲目命中的 key 合成**一条** `kind = ORPHAN`，`name = "其他"`、`id = "orphan"`、`trackIds` = 那些 key 本身（反解不出 trackId，就把能删的东西原样记下来）。
  - 专辑名为空的歌归到「未知专辑」，不丢歌；算不出 `cacheKey` 的歌直接跳过，不算孤儿。
  - `keys` 为空返回空列表——调用方据此不写空名单。
- `app/src/main/java/com/example/pulsar/JellyfinViewModel.kt`
  - 构造参数追加 `downloadLedgerStore: DownloadLedgerStore? = null`。
  - 新增私有 `migrateLegacyCopies(tracks)`：先查 `migrationDone()`（是就返回）→ `completeKeys()` → 调纯函数 → **合并**写（`load() + entries`，按 `serverUrl+userId+kind+id` 去重后 `replaceAll`，不是整表覆盖，免得抹掉别的账号/别的来源的条目）→ `markMigrationDone()`。空名单只置标记不写盘。写失败 `Log.w` 后仍置标记（「失败不重试」）。
  - 三个挂载点：`login()` 成功、`restoreSavedLogin()` 成功、`goOnline()`（离线启动时曲库没拉到，探活成功后补跑）。`tracks == null` 的路径压根不会调它。
- `app/src/main/java/com/example/pulsar/JellyfinViewModelFactory.kt`：把 `DataStoreDownloadLedgerStore(application.pulsarPreferencesDataStore)` 传进去。**没碰 `PulsarApplication.kt`**（归另一个子代理），所以直接在工厂里用现有的 DataStore 扩展属性构造。
- `app/src/test/java/com/example/pulsar/DownloadMigrationTest.kt`（新）：7 个纯函数测试——按专辑归组、只命中一首、单个/多个 orphan、空缓存、算不出 key 跳过、空专辑名。
- `app/src/test/java/com/example/pulsar/JellyfinViewModelTest.kt`：新增 `FakeDownloadLedgerStore` + 5 个用例（只跑一次、空缓存不写名单、曲库未就绪不跑、写失败不重试不影响曲库、迁移合并写不抹已有条目、离线探活后补跑）。

**需要注意的点**

1. **「其他」组是一条还是多条**：规格原文是「反查不到的 → 单独一条 `kind = orphan`」，所以所有反查不到的 key 合成一条，`trackIds` 里存 key。逐首删除按 `trackIds` 元素删即可，不用多条 entry。
2. **专辑 id**：`Track` 只有 `album`（名字）没有 albumId，所以 `DownloadEntry.id` 用专辑名。工单 05 说「删除走名单里的那批」，按名字分组够用；若以后要按 id 精确匹配，得先给 `Track` 加字段。
3. `JellyfinViewModelFactory.kt` 不在工单 07 的允许清单里，但它是唯一的接线点（另一个子代理占着 `PulsarApplication.kt`），改动只有一行构造参数。reviewer 若希望改成 `PulsarApplication.downloadLedgerStore()` 工厂方法，可以在合并后统一收口。

