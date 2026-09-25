# 01 — 音频缓存落地

**What to build:** 听过的歌自动在手机上留一份副本。播一首歌 → 手机存储里真的多出那段字节；听到一半切走 → 留下的就是半截。做完这一票，「离线能听」的底座就有了，只是还没有界面能让你看见它。

**Blocked by:** None — can start immediately.

**Status:** ready-for-human（代码完成，真机验证项待做）

- [x] `app/build.gradle.kts` 显式加上 `media3-datasource` 和 `media3-database`（1.5.1，跟现有三个 artifact 同版本）——它们现在一个是传递依赖、一个根本没进来
- [x] 用 `SimpleCache` + `LeastRecentlyUsedCacheEvictor(5 GB)` 建缓存实例，落在 **`filesDir`**（**不是** `cacheDir`——见规格：`cacheDir` 会被系统在空间紧张时清掉，也会被设置里的「清除缓存」整锅端）
- [x] 在现有 `AuthHttpDataSourceFactory` 外面套一层 `CacheDataSource.Factory`，接给 `DefaultMediaSourceFactory`
- [x] 打开 `FLAG_IGNORE_CACHE_ON_ERROR`：读缓存出错就退回网络（**铁律 1**：缓存是增强，不是依赖）
- [x] `SimpleCache` 必须是**单例**（同一个目录只能有一个实例），并在 `PlaybackService` 里正确释放
- [ ] **USB 独占模式下真机验证**：缓存命中时位深协商、静音跳曲、回放增益跟在线听完全一致
- [ ] 真机验证：播一首 FLAC → 缓存目录里多出约 36 MB；**再播一遍缓存总量不增长**（说明走的是本地）
- [ ] 真机验证：缓存超过 5 GB 时最旧的被淘汰，且**正在播的那首不被淘汰**
- [ ] **核对「完整副本」判据的前提**：播一首歌之后，`ContentMetadata.getContentLength(cache.getContentMetadata(key))` **不是 -1**（证明 Jellyfin 的 `?static=true` 直传流确实带 `Content-Length`）。**拿到 -1 就立刻在 Comments 里记一笔**——那意味着离线永远标灰，判据得换
- [ ] 故意把缓存目录搞坏（占位文件 / 删目录）→ 播放**照常走网络**，不崩、不报错、不显示任何异常
- [x] 测试：`MediaCache` 接口与它的假实现；播放链路里缓存相关的分支

## Comments

**实现记录（2026-09-25，agent）：**

- 缓存实例在 `PulsarApplication.mediaCacheStore`（进程级单例）：服务写、界面查共用。**服务 onDestroy 不释放它**——释放会让界面侧的查询全变异常；它的生命周期就是进程。
- `SimpleCacheMediaCache` 包住 `SimpleCache`，`guarded` 吞掉一切异常（铁律 1）。
- 「完整副本」判据 = `ContentMetadata.getContentLength() > 0 && cache.isCached(key, 0, total)`，拿不到总长一律按不完整（`MediaCacheTest` 钉死）。
- 剩余未勾项全部是真机验证（缓存目录增长、LRU 淘汰、`exo_len` 非 -1、搞坏目录照常播放），需要装机后逐条做。

