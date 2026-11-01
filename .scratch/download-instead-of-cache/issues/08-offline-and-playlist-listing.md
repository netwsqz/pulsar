# 08 — 离线判定改口径 + 已下载播放列表的本地清单

**What to build:** 离线时「哪些歌能放」的判定从「有完整副本」正式改成「已下载」，并且让下载过的播放列表在离线时也能打开。

**Blocked by:** 03 — 下载名单 / 04 — 下载调度器

**Status:** ready-for-human（代码完成）

- [x] `computeCachedTrackIds` → 语义改成「已下载的曲目 id」，判据仍是字节真相（`hasCompleteCopy`），**不走名单**
- [x] 离线时只有这一批能放。（标灰已于 ADR-0008 废止：现在只显示已下载的，没下载的不出现在列表里）
- [x] 退出登录 / 会话过期**不清**这些（用户故事 16）；现有 `signOut` 里调 `clearLocalCopyAndSnapshot` 的那段要拆开——快照照清，下载不清
- [x] 按账号过滤：`serverUrl + userId` 变了就看不见上一批（用户故事 17）
- [x] **已下载播放列表存一份本地清单**（列表名 + 曲目表），落在现有 `library-snapshot.json` 里新增一节（跟 `CONTEXT.md`「已下载列表清单」对齐）
- [x] 离线时打开已下载的播放列表 → 正常显示；未下载的 → 照旧「需要联网」
- [x] 测试：下载 → 退出登录 → 重登，下载还在且能放；换账号后看不见；离线播放列表按下载状态分流

## Comments

### 实现记录（2026-10-25，agent）

**口径**：离线可播 = **字节真相 ∩ 名单可见性**。
`computeCachedTrackIds` → `computeDownloadedTrackIds`：`completeKeys()`（字节）筛出候选，
再用 `downloadLedgerStore.entriesFor(serverUrl, userId).flatMap { trackIds }`（可见性）求交集。
名单没注入 / 读失败时**退化成只看字节**（无从判断归属时宁可能放，不误标灰）。
`JellyfinUiState.cachedTrackIds` 字段名保留（UI 侧 4 处引用不动），只改了文档注释。

**按账号隔离**：字节层 `completeKeys()` 全局（键里没有 userId），所以隔离只能靠名单；
`downloadedPlaylists` 条目也带 `serverUrl + userId`，`replaceSnapshot` 只保留当前账号那几条。

**已下载列表清单**：`LibrarySnapshot.downloadedPlaylists: List<DownloadedPlaylist>`，默认
`emptyList()`（旧快照解码不失败，有测试钉死）。写入时机两处：在线打开列表详情（拉到的曲目表）
与 `rememberDownloadedPlaylist(...)`（供工单 05 下载成功后调）。只写**名单里有 PLAYLIST 条目**的列表。
`persistSnapshot` 重建快照时原样带上这一节（它不是服务器数据，不能被覆写抹掉）。

**离线播放列表**：`loadPlaylists()` / `loadPlaylistTracks()` 在 `isOffline` 时走本地清单；
一个都没下载过时 `playlists` 保持 `null` + `errorMessage = "播放列表需要联网才能查看"`，
未下载的单个列表给「这个播放列表没有下载，需要联网才能查看」。

**测试**：`JellyfinViewModelTest` 新增 10 个用例（交集判定、半截不算、换账号看不见、
退出重登仍在、离线列表分流、账号过滤、在线写清单 / 非下载列表不写），
`LibrarySnapshotTest` 新增 3 个（往返、旧 JSON 兼容、按账号过滤）。全量 661 个用例通过。
两个既有用例的断言做过调整：`断网时点播放列表屏明说需要联网` 原本靠 `goOffline(needsNetwork=true)`
给文案，现在 `loadPlaylists()` 在离线态直接短路，于是改由 `showOfflinePlaylists()` 给同一句文案。

**未做（UI 部分留给后续）**：`MainActivity.kt` 里播放列表详情页的「需要联网」分支读的是
`state.errorMessage`，现在能拿到更具体的文案，无需改动；但如果要做「已下载」入口的徽标/按钮，
需要改 `MainActivity`。本票没有碰 `MainActivity.kt`。


### 口径变更（2026-10-28）

离线口径已由「全量 + 标灰」改为「只显示已下载」，见 `docs/adr/0008-offline-shows-only-downloads.md`。
本票里凡是提到「标灰」「点了告诉用户为什么」的表述都以此为准；`computeDownloadedTrackIds` 的语义不变，
只是消费方从「TrackRow 的 dimmed 分支」换成了界面层的 `offlineVisibleLibrary()`。
