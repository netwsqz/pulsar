# OfflinePolicy 重构执行计划

> 状态：**待研究**。本文是 candidate 2（离线策略收成一个 module）的执行规格，不是 ADR。
> 相关：`docs/playback-session-refactor.md`、`docs/download-library-refactor.md`、`docs/library-state-refactor.md`。
> ADR 边界：**ADR-0008 明确决定「离线过滤在界面层派生、原始 state 不动」**。本方案不改变这条，只是把散落的派生收进一个由界面调用的纯 module。

---

## 1. 目标

把「离线时能看见什么、能不能播、哪一节不显示、提示什么」收进一个纯 module：

- **一个小 interface**：`OfflineInput -> OfflineView`。
- **一条策略**：看不见 / 不能播 / 不显示，都来自同一份判据。
- **可整块测试**：不需要 Compose，不需要真实下载，不需要网络。
- **界面只渲染**：不再自己组合 `isOffline + cachedTrackIds + offlineNotice + isOfflineFiltered`。

## 2. 非目标

- 不改 `JellyfinUiState` 的字段（那是 candidate 3 LibraryState 的事）。
- 不改下载字节 / 名单的判定来源（candidate 4 DownloadLibrary 的事）。
- 不改 `LibrarySnapshot` 的读写格式。
- 不改离线探活（`startOfflineRetry`）的节奏与上限。
- 不改任何视觉、文案风格（只把重复文案收口，不改字）。
- 不把离线过滤改成「在线也过滤」；在线仍是全量。

## 3. 现状证据

| 位置 | 现状 |
|---|---|
| `JellyfinUiState.kt:75–106` | 离线相关字段：`isOffline`、`cachedTrackIds`、`isOfflineSearch`；下载相关字段也混在同一 state |
| `JellyfinViewModel.kt:346–356` | `isPlayable` / `unplayableReason`：离线判定的播放入口 |
| `JellyfinViewModel.kt:1241–1284` | `computeDownloadedTrackIds`（字节 ∩ 名单）、`visibleDownloadedTrackIds`、`knownTracks` |
| `JellyfinViewModel.kt:1290–1363` | `goOffline` / `goOnline` / `markOnline` 三套字段清理 |
| `OfflineVisibleLibrary.kt:47–92` | `offlineVisibleLibrary()`：6 个列表的过滤 + 专辑反推 |
| `DiscoverSections.kt:73–96` | `discoverSections()`：离线时三节历史整节不画 |
| `CachedAlbums.kt:19–32` | `groupCachedByAlbum()`：按专辑名分组 |
| `LibraryGroups.kt:24–47` | `groupAlbums()`：又一份按专辑名分组 |
| `MainActivity.kt:870–890` | `offlineFilter = isOffline && cachedTrackIds != null`，然后调 `offlineVisibleLibrary` |
| `MainActivity.kt:951` | `offlineNotice = if (offlineFilter) "离线 · 只显示已下载" else null` |
| `MainActivity.kt:2497–2685` | 发现页 gating：`isOffline` 与 `historyLoading` 两套判断 |
| `MainActivity.kt:3333 / 3732` | 详情页用 `offlineNotice != null` 当 `isOfflineView` 布尔代理 |
| `MainActivity.kt:1013–1019` | 多选「全选」用原始列表（`state.albumTracks` 等），与渲染的 `visible.*` 不一致 |

离线标志至少六个：`isOffline`、`cachedTrackIds`、`offlineFilter`、`isOfflineFiltered`、`offlineNotice`、`isOfflineSearch`。`isOffline` 在 4 个文件出现 39 次。

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 形态 | 纯函数 + 数据类，不持有状态、不做 IO |
| D2 | 输入 | `OfflineInput`：`isOffline`、`downloadedTrackIds`（null = 未知）、六份原始列表、`tracksLoaded` / `isLoading` |
| D3 | 输出 | `OfflineView`：`visibleLibrary`、`isFiltering`、`notice`、`discover`、`emptyKind` |
| D4 | 播放入口 | `isPlayable` / `unplayableReason` 也由 policy 提供；ViewModel 委托，不自己判 |
| D5 | 专辑身份 | 同一个 module 提供 `AlbumIdentity`（按专辑名匹配 + 从曲目反推卡片），三处共用 |
| D6 | 文案 | 保留现有字面量；只收口成 `OfflineNotice` / `OfflineEmpty` 类型，不改字 |
| D7 | 依赖关系 | `downloadedTrackIds` 暂时由 ViewModel 传入；candidate 4 落地后改成 `DownloadLibrary` 提供 |

## 5. 目标形状（sketch）

```kotlin
data class OfflineInput(
    val isOffline: Boolean,
    /** null = 还没算出来（不筛）；空集 = 确实一首都没有。 */
    val downloadedTrackIds: Set<String>?,
    val tracks: List<Track>?,
    val albums: List<Album>?,
    val discoverAlbums: List<Album>?,
    val albumTracks: List<Track>?,
    val playlistTracks: List<Track>?,
    val searchResults: SearchResults?,
    val tracksLoaded: Boolean,
    val isLoading: Boolean,
)

data class OfflineView(
    val visibleLibrary: VisibleLibrary,
    /** true = 列表真的被筛过（等价于旧 offlineFilter），用于文案与下载按钮显隐。 */
    val isFiltering: Boolean,
    val notice: OfflineNotice?,
    val discover: DiscoverSections,
    val emptyKind: DiscoverEmpty?,
)

enum class OfflineNotice {
    VisibleOnly,        // 离线 · 只显示已下载
    NeedsNetwork,       // 播放列表需要联网才能查看
}

data class AlbumIdentity(
    val key: String,          // 归一化后的专辑名（大小写/空白）
    val displayName: String,
    val artist: String,
)

fun offlineView(input: OfflineInput): OfflineView
fun offlineIsPlayable(track: Track, downloadedTrackIds: Set<String>?, isOffline: Boolean): Boolean
fun offlineUnplayableReason(track: Track, downloadedTrackIds: Set<String>?, isOffline: Boolean): String?
fun albumIdentityOf(track: Track): AlbumIdentity
fun albumsFromTracks(tracks: List<Track>): List<Album>
```

设计要点：
- `downloadedTrackIds == null` 时**不筛**（与今天一致，避免把能播的歌藏掉）。
- `OfflineView.visibleLibrary` 仍保持 null = 没拉到、空列表 = 拉到但为空（骨架 vs 空态）。
- `isFiltering = isOffline && downloadedTrackIds != null`，只在 module 里算一次。
- `OfflineNotice` / `OfflineEmpty` 是 UI 类型，但值由 module 决定；界面只 `when`。

## 6. 执行步骤

### Step 0 — 基线 + 行为快照

1. 跑全量单测，记录基线。
2. 给现有纯函数补一组「行为快照」测试（如果已覆盖就跳过）：
   - `OfflineVisibleLibraryTest` 已覆盖 null/空/反推专辑；
   - `DiscoverSectionsTest` 已覆盖三节 gating；
   - `CachedAlbumsTest` / `LibraryGroupsTest` 已覆盖分组。
3. 不碰生产代码。

**验收**：全量测试通过；知道哪些行为已经被钉住。

### Step 1 — 引入 `OfflinePolicy`，路由界面派生（行为不变）

1. 新增 `app/src/main/java/com/example/pulsar/offline/OfflinePolicy.kt`、`OfflineModels.kt`。
2. `MusicLibraryScreen` 里把 `offlineFilter` + `visible` 的 if/else 换成 `offlineView(...)`。
3. `offlineNotice` 从 `OfflineView.notice` 取；`isOfflineFiltered` 从 `isFiltering` 取。
4. 保留旧 `offlineVisibleLibrary` / `discoverSections` 作为内部委托，先不删。

**TDD**
- RED：`OfflinePolicyTest` 覆盖：
  - 在线：全量、不筛、无提示；
  - 离线 + downloadedIds=null：不筛、无提示；
  - 离线 + 空集：全部空、`isFiltering=true`、`VisibleOnly`；
  - 离线 + 部分：只留命中的曲目/专辑（有一首已下载就保留专辑）；
  - 离线 + albums=null：从已下载曲目反推专辑卡；
  - 离线 + 三节历史：`discover.showRecentlyPlayed/MostPlayed/RecentlyAdded` 全 false；
  - 离线 + 一首都没下载：`emptyKind = Downloaded`。
- GREEN：实现。
- 更新 UI 调用点，跑测试。

**验收**：所有离线屏显示与改前一致；真机断网看一遍。

### Step 2 — 把 `isPlayable` / `unplayableReason` 收进 policy

1. `JellyfinViewModel.isPlayable` / `unplayableReason` 改成调用 `offlineIsPlayable` / `offlineUnplayableReason`。
2. 播放守门（`onPlayTrack` / `onAddToQueue`）仍然调 ViewModel，不动调用方。
3. 测试：现有 `JellyfinViewModelTest` 的离线可播断言保持绿。

**验收**：离线点未下载歌的提示与改前一字不差；在线一律可播。

### Step 3 — 统一专辑身份（三处分组）

1. `offlineVisibleLibrary` 的 `toAlbumCard` / `downloadedAlbumNames`；
2. `groupCachedByAlbum`；
3. `LibraryGroups.groupAlbums` 的按名归并。
   三处改成 `AlbumIdentity` + `albumsFromTracks` 的统一实现。
2. 补测试：大小写/空白/同名不同歌手/UNKNOWN_ARTIST 的边界（沿用 `LibraryGroupsTest` 的规则）。
3. 删除重复实现。

**风险**：三处当前对「同名不同歌手」的合并代价一致（都按专辑名），但要确认 `LibraryGroups` 是按艺术家分组、不是按专辑名——别把两个概念混了。`AlbumIdentity` 只服务「专辑内曲目/已下载分组」，不要改艺术家分组规则。

**验收**：三个测试类全绿；离线专辑墙 / 已下载列表 / 艺术家组详情无变化。

### Step 4 — 收口提示与空态文案

1. 把 `"离线 · 只显示已下载"`、`"离线 · 手机里还没有下载的专辑"`、`"离线 · 手机里还没有下载的歌曲"` 收进 `OfflineNotice` / `OfflineEmpty.message()`。
2. 搜索结果页的离线提示也走同一套（现在 `SearchContent` 自己写了一行）。
3. 不改字，只改出处。

**验收**：全 App 搜索这三个字符串只剩一处定义；真机对照文案无变化。

### Step 5 — 清理 + 文档

1. 删除旧的重复 helper（保留测试覆盖）。
2. 更新 `CLAUDE.md` 的离线章节：加 `OfflinePolicy` 的职责与输入/输出。
3. 更新 `CONTEXT.md`：如果新增「离线视图（OfflineView）」这个术语，写进去；否则不造词。
4. 全量测试 + `assembleDebug` + 真机清单。

## 7. 测试策略

- **纯函数测试为主**：`OfflinePolicyTest` 覆盖组合矩阵，不碰 Compose/IO。
- 现有 `OfflineVisibleLibraryTest` / `DiscoverSectionsTest` 保留，作为内部实现的行为钉。
- ViewModel 测试只保留「离线可播 / 不可播 / 提示文案」的集成断言。
- 不新增 Compose 测试（项目没有设施）。

## 8. 真机验收清单

- [ ] 在线：发现 / 歌曲 / 专辑 / 艺术家 / 专辑详情 / 播放列表详情 全部全量显示
- [ ] 断网：只显示已下载；每屏标题下有一行「离线 · 只显示已下载」
- [ ] 断网：专辑有一首已下载就显示，详情只列已下载曲目
- [ ] 断网：离线搜索只搜已下载，标记「离线搜索」
- [ ] 断网：三节服务器历史整节不显示（骨架和空态都没有）
- [ ] 断网：一首都没下载时显示专用空态
- [ ] 断网：点未下载歌 → 提示，不写队列
- [ ] 恢复网络：探活后自动恢复全量，不用手动刷新
- [ ] 多选「全选」在离线时只选可见曲目

## 9. 风险与待确认

| 风险 | 缓解 |
|---|---|
| 把策略收进 module 后，界面仍然自己拼布尔 | UI 只读 `OfflineView`，禁止再算 `isOffline && cachedTrackIds` |
| `downloadedTrackIds` 的来源与 DownloadLibrary 重叠 | 先以输入形式接；candidate 4 落地后由 DownloadLibrary 提供 |
| 专辑名匹配的代价（同名不同歌手合并） | 明确写在 module 文档里；不试图用 id 解决（Track 没有专辑 id） |
| 改文案收口时不小心换了字 | 测试钉死字面量；真机对照 |

**待确认**
1. `OfflinePolicy` 放 `com.example.pulsar.offline` 还是 `com.example.pulsar` 根包？推荐 `offline/`。
2. `OfflineView` 是否要包含 `playable` 集合，还是保留 `offlineIsPlayable` 函数？推荐函数，避免快照过期。
3. 是否顺手修「多选全选用原始列表」的 bug？推荐单独 ticket，别混进纯函数提取。

## 10. 新窗口开工提示词

```
按 docs/offline-policy-refactor.md 执行。先做 Step 0 基线 + 行为快照，再做 Step 1：
新增 offline/OfflinePolicy.kt 与 OfflineModels.kt，把 MusicLibraryScreen 的 offlineFilter /
visible / offlineNotice 换成 offlineView(...)。行为不变，先写 OfflinePolicyTest 再实现。
完成后跑全量单测 + assembleDebug，报告改动与验证结果。
```
