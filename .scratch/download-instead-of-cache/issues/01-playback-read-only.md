# 01 — 播放链路改纯只读，淘汰器换 NoOp

**What to build:** 听歌不再在手机上留下任何东西。听完一整张专辑，存储占用一个字节都不涨。这是整个改造的地基——不做这一步，后面所有「下载」的账都算不清。

**Blocked by:** None — can start immediately.

**Status:** ready-for-human（代码完成）

- [x] `PlaybackService.cacheDataSourceFactory()` 加 `.setCacheWriteDataSinkFactory(null)`（只读模式）
- [x] **先验证** `createDataSourceForDownloading()` 是否也受 `cacheIsReadOnly` 影响 → **不受影响**（media3 1.5.1 bytecode 已确认：`createDataSourceForDownloading()` 无视 `cacheIsReadOnly`，照样建写 sink）。播放只读和下载可写能共用一个 cache 实例，**不需要**给下载单独建 factory。
- [x] `PulsarApplication`：`LeastRecentlyUsedCacheEvictor(5 GB)` → `NoOpCacheEvictor`，删掉 `kMediaCacheMaxBytes`
- [ ] 真机验证：播一整张专辑 → 设置页占用数字不变（待人工）
- [ ] 真机验证：已下载的歌播放时**仍然走本地**（断网能放，说明读路径没被只读模式搞坏）（待人工）
- [x] 测试：`MediaCache` 现有测试全绿（接口没动，行为不该变）

## Comments

### 实现记录（2026-10-25，agent）

**改动：**

- `PlaybackService.kt` `cacheDataSourceFactory()` 增加 `.setCacheWriteDataSinkFactory(null)`；KDoc 从「边读边写、LRU 自己管」改成「纯只读，写盘只有下载一条路」。行为：命中读本地、未命中走网络，`FLAG_IGNORE_CACHE_ON_ERROR` 不变。
- `PulsarApplication.kt`：`LeastRecentlyUsedCacheEvictor(kMediaCacheMaxBytes)` → `NoOpCacheEvictor()`；删除 `kMediaCacheMaxBytes`；更新目录注释与 `mediaCacheStore` KDoc（明确「不自动淘汰、无上限」）。

**为什么没新增单测：** 本工单是配置/接线改动，没有新逻辑分支。只读标志和淘汰器是 media3 内部行为，仓库约定「不测 `SimpleCache` / `CacheWriter` 自己的行为」，硬造测试只能测 mock 调用、没有价值。现有 `MediaCacheTest`（4 个）全绿，全量单测 617 个通过、0 失败。

**待人工：** 两条真机验证（占用不涨、断网能放已下载）留给人在设备上确认。

**顺带发现（未改，超出本工单范围）：** `MediaCache.kt` 第 11、75 行注释仍写着「自动产生、满了自动淘汰最旧的 / LRU 淘汰」，与新的只读 + NoOp 行为矛盾，建议在 `MediaCache` 改名 `MediaDownloads` 的工单里一并清理。
