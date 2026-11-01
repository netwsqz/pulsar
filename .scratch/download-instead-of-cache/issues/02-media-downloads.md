# 02 — 下载能力：MediaCache → MediaDownloads

**What to build:** 给缓存层加一条「主动往缓存里塞一首歌」的能力，接口改名。做完这一票，代码里能下一首歌了，只是还没有 UI 能点。

**Blocked by:** 01 — 播放链路改纯只读（要先确认只读 factory 与下载 factory 是分开的）

**Status:** ready-for-human（代码完成）

- [x] `MediaCache` 改名 `MediaDownloads`，`SimpleCacheMediaCache` → `SimpleCacheMediaDownloads`
- [x] 加 `download(url: String, key: String, onProgress: (Long, Long) -> Unit): Boolean`
      —— 内部用 `CacheWriter` + `createDataSourceForDownloading()`，放 IO dispatcher
- [x] **删掉 `clear(keepKeys)`** —— 没有「一键清空」了（spec 铁律 2）
- [x] 取消：`download` 返回前能被协程取消；`CacheWriter.cancel()` 要在 `finally` 里调
- [x] `guardedIo` 兜底照旧：下载失败返回 false，绝不抛（铁律 4）
- [x] 测试：`FakeMediaDownloads` 与接口同步；`SimpleCacheMediaDownloads` 的失败路径（异常 → false）

## Comments

### 实现记录（2026-10-25，agent）

**改了什么**

- `MediaCache.kt` → `MediaDownloads.kt`（git mv）：接口 `MediaDownloads`、实现 `SimpleCacheMediaDownloads`；
  `isCompleteCopy` / `guardedIo` 原名保留。类注释从「自动产生、LRU 淘汰」改成「用户主动下载、永不自动淘汰、播放纯只读」。
- 新增 `download(url, key, onProgress)`：`CacheDataSource.Factory().setCache(cache)
  .setUpstreamDataSourceFactory(upstreamFactory).createDataSourceForDownloading()` +
  `CacheWriter`，`DataSpec` 显式 `setKey(key)`（不显式设会退回用 URL 当键，跟播放侧对不上）。
  进度换算成 `(bytesCached, requestLength)`。
- 实现类构造函数多了一个 `upstreamFactory: DataSource.Factory`：`CacheDataSource` 必须要上游才能下载，
  由 `PulsarApplication.mediaDownloads()` 注入 `AuthHttpDataSourceFactory { activeAuthorization }`
  （token 只进 header，跟播放链路同一个来源）。
- 删除 `clear(keepKeys)` 及其全部调用方；`JellyfinViewModel.clearLocalCopyAndSnapshot` 现在只清快照，
  **不再清下载**（这也顺带对齐了 ADR-0007「退出登录不清下载」）。
- `JellyfinViewModel` 字段 `mediaCache` → `mediaDownloads`；`clearCache()` 保留为空壳 + TODO，
  文案点名归工单 06 删按钮。
- `PulsarApplication`：`mediaCache()` → `mediaDownloads()`；常量 `kMediaCacheDirName` → `kMediaDownloadsDirName`
  （**目录名仍是 `media_cache`**，存量副本原地就是下载，铁律 7）。
- `JellyfinViewModelFactory` 跟着改参数名。
- 测试：`MediaCacheTest.kt` → `MediaDownloadsTest.kt`（+1 个失败路径用例）；`FakeMediaCache` →
  `FakeMediaDownloads`，去掉 `clear` 后清掉 `cleared` / `lastClearKept` 两个字段。

**取消的实现方式（值得看一眼）**

`CacheWriter.cache()` 是同步阻塞循环，只看自己的 `isCanceled`，不看协程状态。协程被取消时线程还堵在里面，
所以 `download` 里起了一个 `launch(start = UNDISPATCHED) { awaitCancellation() }` 的看门狗协程，
它的 `finally` 调 `writer.cancel()`；正常跑完时看门狗被 `cancel()` 走同一路径（`cancel` 幂等）。
外层 `finally` 里也再调一次 `writer.cancel()`。协程取消照常上抛，不被 `guardedIo` 吞掉。

**验证**

`/c/Tools/gradle-8.10.2/bin/gradle.bat --no-daemon :app:testDebugUnitTest --rerun-tasks` →
`BUILD SUCCESSFUL`，**618 个用例通过 / 0 失败 / 0 错误**（`MediaDownloadsTest` 5 个、
`JellyfinViewModelTest` 78 个）。

**留给后续工单的**

- 设置页「清空」按钮与二次确认弹窗、`JellyfinUiState` 的 `cacheMessage` / `isCacheBusy` / `cacheBytes` /
  `cacheTrackCount` 四个字段、`MediaCacheSection` 改名 —— 全部归**工单 06**（本工单按约定不动 MainActivity 的 UI）。
- 退出登录不清下载后，跨账号可见性隔离归**工单 08**。
- `PulsarApplication.mediaCacheStore` 这个属性名保留了（它确实是 `SimpleCache` 实例，spec 也这么叫），
  只改了目录常量与工厂方法名。
- `DownloadLedger.kt` 第 102 行注释还写着「不改 `MediaCache.kt`」，文件名已变；该文件不在本工单范围，未动。
