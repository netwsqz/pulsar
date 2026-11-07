# LibraryState 重构执行计划

> 状态：**待研究**。本文是 candidate 3（曲库加载与状态收成 LibraryState）的执行规格，不是 ADR。
> 相关：`docs/offline-policy-refactor.md`、`docs/download-library-refactor.md`、`docs/unified-search-refactor.md`。
> 与 CLAUDE.md 的「单一 StateFlow<JellyfinUiState>」约定有冲突：本方案保留单一 StateFlow，但把内部拆成多个 state slice。动之前先确认这条约定可以改。

---

## 1. 目标

把 38 字段的大 state 和 46 个公开方法，按**所有权**拆成三个 state module：

- `SessionState`：登录、恢复、过期、凭据表单。
- `LibraryState`：曲库、发现、详情、搜索、多选、文件操作。
- `DownloadState`：下载、删除进度、占用统计。

每个 module 拥有自己的：
- 加载 job / 代次（每节独立，不再共享一个 `albumsJob`）；
- `null / 空列表 / 失败` 三态语义；
- reset / offline / error 规则。

界面只收自己需要的 slice；`JellyfinViewModel` 只做组合。

## 2. 非目标

- 不改 ADR-0007 / ADR-0008 的下载与离线语义。
- 不把 `QQDownloadViewModel` 一起拆（它是独立 ViewModel，另开 ticket）。
- 不引入导航库、DI 框架。
- 不改 UI 的视觉与交互。
- 不一次性重写所有屏幕；用 delegating properties 做渐进迁移。

## 3. 现状证据

| 位置 | 现状 |
|---|---|
| `JellyfinUiState.kt` | 38 个字段；7 个独立 busy 标志；4 个消息通道 |
| `JellyfinViewModel.kt` | 46 个公开函数 + 4 个 StateFlow；83 次 `_state.value = ...copy(...)` |
| `JellyfinViewModel.kt:733–768` | `loadLibrary` 是所有加载的统一入口；`albumsJob` 被六个入口共享 |
| `JellyfinViewModel.kt:741` | 每次新调用取消上一个 `albumsJob` → 加载播放列表会取消专辑列表 |
| `MainActivity.kt:842–865` | UI 用 `LaunchedEffect(section, sessionReady, isOffline, historyEpoch)` 自己编排加载 |
| `JellyfinViewModel.kt:311–332` | `signOut` 用整份 `JellyfinUiState(...)` 重建 |
| `JellyfinViewModel.kt:1606–1651` | `expireSession` 手抄 27 个字段清理 |
| `JellyfinViewModel.kt:1317–1363` | `goOnline` / `markOnline` 各抄 11 个字段 |
| `JellyfinViewModel.kt:127–129` | 三个 setter 只做 `copy`；一事件一方法 |
| `JellyfinViewModel.kt:1653–1663` | `clearAlbumDetail` / `clearPlaylistDetail` 几乎相同 |
| `JellyfinUiState.kt:54,57` | `selectedAlbum` / `selectedPlaylist` 无生产 UI 读取，导航在 `LibrarySection` |
| `JellyfinViewModel.kt:87` | `playbackTracks` 无生产 UI 读取，只有测试读 |
| `MainActivity.kt:811–817` | `section` / `playbackMode` 用 `remember` 而非 `rememberSaveable`，转屏/重建即丢 |
| `MainActivity.kt:1011` | `batchDeleteTargets` 声明后从未读取（死状态） |
| `JellyfinUiState.kt:24,41,64,69,106` | `errorMessage` / `fileOpsMessage` / `recycleMessage` / `downloadDeleteMessage` 四个消息通道 |
| `MainActivity.kt:3096–3123` 等 | 三态 UI 写了 10 遍；`TracksContent` 漏了 error 分支 |

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | 外层形状 | 保留 `JellyfinUiState` 作为组合根，内部嵌套 `session / library / downloads` 三个 slice |
| D2 | 迁移方式 | 先加嵌套字段 + delegating properties，屏幕逐个迁移，最后删 delegation |
| D3 | 加载编排 | 新增 `LibraryCache` class，拥有每节 job + generation + 三态语义；ViewModel 委托 |
| D4 | 每节 job | 每节独立 `Job`（albums / discoverAlbums / recentlyPlayed / mostPlayed / recentlyAdded / albumTracks / playlists / playlistTracks / search），不再共享 `albumsJob` |
| D5 | 错误通道 | `errorMessage` 拆成每个 slice 的类型化 error；登录错误留在 `SessionState` |
| D6 | reset 语义 | `SessionState.clear()` / `LibraryState.clearCaches()` / `DownloadState` 不随登出清（ADR-0007） |
| D7 | 导航状态 | `selectedAlbum` / `selectedPlaylist` 删除；`LibrarySection.AlbumDetail(album)` 已经是真相 |
| D8 | 测试 | 先保留 `JellyfinViewModelTest` 作为集成面，新建 `LibraryCacheTest` 测每节加载；迁移完再拆测试文件 |

## 5. 目标形状（sketch）

```kotlin
data class JellyfinUiState(
    val session: SessionState = SessionState(),
    val library: LibraryState = LibraryState(),
    val downloads: DownloadState = DownloadState(),
) {
    // 迁移期兼容层：先保留旧读法，屏幕迁完删掉
    val tracks: List<Track>? get() = library.tracks
    val albums: List<Album>? get() = library.albums
    // ...
}

data class SessionState(
    val serverUrl: String = "",
    val serverUrlHistory: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    val isRestoring: Boolean = false,
    val isLoading: Boolean = false,
    val error: SessionError? = null,
)

data class LibraryState(
    val tracks: List<Track>? = null,
    val albums: List<Album>? = null,
    val discoverAlbums: List<Album>? = null,
    val albumTracks: List<Track>? = null,
    val selectedAlbum: Album? = null,
    val playlists: List<Playlist>? = null,
    val playlistTracks: List<Track>? = null,
    val selectedPlaylist: Playlist? = null,
    val recentlyPlayed: List<Track>? = null,
    val mostPlayed: List<Track>? = null,
    val recentlyAdded: List<Album>? = null,
    val failedSections: Set<LibrarySectionKey> = emptySet(),
    val historyEpoch: Int = 0,
    val isDiscoverRefreshing: Boolean = false,
    val search: SearchState = SearchState(),
    val multiSelectIds: Set<String> = emptySet(),
    val fileOps: FileOpsState = FileOpsState(),
)

data class DownloadState(
    val cachedTrackIds: Set<String>? = null,
    val cachedAlbums: List<CachedAlbum>? = null,
    val cacheBytes: Long = 0,
    val cacheTrackCount: Int = 0,
    val isDeleting: Boolean = false,
    val deleteMessage: String? = null,
)

enum class LibrarySectionKey { RecentlyPlayed, MostPlayed, RecentlyAdded }
```

```kotlin
class LibraryCache(
    private val apiProvider: () -> JellyfinApi?,
    private val sessionProvider: () -> Session?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    val state: StateFlow<LibraryState>
    fun onSectionEntered(section: LibrarySection)
    fun refresh(section: LibrarySection)
    fun clearCaches()
    fun applySnapshot(snapshot: LibrarySnapshot)
    fun leaveOffline(tracks: List<Track>)
}
```

设计要点：
- `LibraryCache.onSectionEntered(section)` 替换 UI 里的 `LaunchedEffect` 分支；UI 只报「进了哪一节」，不再知道 load 方法名、幂等守卫、session ready。
- 每节一个 job；同一节的重复进入取消旧请求，不同节互不取消。
- `null = 没拉到 / 失败`、`emptyList = 拉到但为空` 的语义写在 `LibraryCache` 里；UI 不再逐字段判断。
- `failedSection` 用 enum，不再用字符串 `"recentlyPlayed"`。
- `SessionState.error` / `LibraryState.error` / `FileOpsState.message` 分开；`errorMessage` 不再承载五种语义。

## 6. 执行步骤

### Step 0 — 基线 + 迁移清单

1. 跑全量单测。
2. 列出 38 个字段各自的读/写位置（可用 `grep`），标出：
   - 生产 UI 读；
   - 只有 VM 内部读；
   - 只写不读（`selectedAlbum` / `selectedPlaylist` / `playbackTracks`）。
3. 列出 16 个收整个 state 的 Composable，按「读几个字段」排序，先迁最简单的。

**验收**：知道每个字段的归属；知道先迁哪 3 个屏。

### Step 1 — 嵌套 state + delegating properties（不改行为）

1. 新增 `SessionState` / `LibraryState` / `DownloadState` / `SearchState` / `FileOpsState`。
2. `JellyfinUiState` 改成组合根，保留旧字段的 delegating getter（只读）。
3. 所有现有代码不改，编译通过，测试全绿。
4. 补一个 `JellyfinUiStateShapeTest`：旧读法与新 slice 写法的值一致。

**验收**：零行为变化；所有旧代码不用改就能编译。

### Step 2 — 抽 `LibraryCache`（加载 + 每节 job + 三态）

1. 把 `loadLibrary` / `loadSection` / 各 `loadX` 的 job + generation 搬进 `LibraryCache`。
2. `JellyfinViewModel` 的 `loadX` 改成 `cache.onSectionEntered(...)` 或 `cache.refresh(...)`。
3. 每节独立 job；删除共享 `albumsJob`（或保留为 `albumListJob` 只服务专辑列表）。
4. `LibraryCacheTest` 覆盖：
   - 同一节重复加载取消旧请求；
   - 不同节互不取消（这是现在的 bug 点）；
   - session 未就绪时静默 no-op，就绪后补跑；
   - 失败保持 null，不写空列表；
   - `isOffline` 翻转时清快照来源的屏。
5. `JellyfinViewModelTest` 的加载相关用例迁到 `LibraryCacheTest`，或保留为集成测试。

**验收**：`grep albumsJob` 只剩一个专辑列表 job；`MusicLibraryScreen` 的 `LaunchedEffect` 分支变薄。

### Step 3 — reset 语义收进 state module

1. `SessionState.clear()`：只清会话/表单/错误。
2. `LibraryState.clearCaches()`：清曲库/发现/详情/搜索/多选/文件操作。
3. `signOut()` / `expireSession()` 改为调这两个方法 + 保留 `serverUrlHistory`；不再手抄字段。
4. `goOnline` / `markOnline` 改为 `library.clearSnapshotBackedScreens()`。
5. 补测试：新增一个字段后，两个 reset 路径都自动清（用反射或显式测试字段集）。

**验收**：`expireSession` / `signOut` 里不再出现 20+ 行字段列表；新增字段不会漏清。

### Step 4 — 屏幕逐个迁到 slice

按 Step 0 的排序，一次迁 2–3 个：
1. `AlbumsContent` / `PlaylistsContent` / `LibraryGroupsContent`（只读 `isLoading` / `error` / 列表）。
2. `TracksContent` / `DiscoverContent`（读 loading / error / 离线相关）。
3. `AlbumDetailContent` / `PlaylistDetailContent` / `SearchContent`。
4. `SettingsContent` / `CachedCopiesContent` / `RecycleBinSection` / `DownloadsSection`。
每个小步都编译 + 真机看一眼；不要求一次迁完。

**验收**：每迁一个屏，旧 delegating property 的读点减少；没有新 bug。

### Step 5 — 删除死字段与旧兼容层

1. 删除 `selectedAlbum` / `selectedPlaylist`（如果确认没有 UI 读）。
2. `playbackTracks` 若仍无生产读者，删除或标 `@VisibleForTesting`。
3. 删除 `batchDeleteTargets`。
4. `section` / `playbackMode` 改 `rememberSaveable`（或跟随 candidate 1 的 session 持有方案）。
5. 删除 delegating properties；所有屏只读 slice。
6. `errorMessage` 的旧读点全部迁到类型化 error。

**验收**：`grep selectedAlbum` 只剩 `LibrarySection.AlbumDetail`；`JellyfinUiState` 不再有 38 个顶层字段。

### Step 6 — 清理 + 文档

1. 更新 `CLAUDE.md` 的 ViewModel / state 章节。
2. 更新 `CONTEXT.md`：加 **曲库状态（LibraryState）**、**会话状态（SessionState）**、**下载状态（DownloadState）** 三个术语（如果确认采用）。
3. 如果决定改「单一 StateFlow」约定，写一条 ADR：**state 按所有权分 slice，组合后仍单一 StateFlow**。
4. 全量测试 + `assembleDebug` + 真机清单。

## 7. 测试策略

- `LibraryCacheTest`：每节 job、幂等、失败语义、离线快照、代次。
- `JellyfinUiStateShapeTest`：slice 与 delegating 一致。
- `SessionStateTest` / reset 测试：登出 / 过期 / 回在线三条路径。
- 现有 `JellyfinViewModelTest` 保留为集成面，逐步瘦身。
- 不新增 Compose 测试。

## 8. 真机验收清单

- [ ] 登录 / 恢复会话 / 退出登录 / token 过期四条路径正常
- [ ] 发现页三节独立加载；一节失败不影响其余
- [ ] 专辑 → 播放列表 → 专辑连续切换，互不取消（改前可能会）
- [ ] 每屏骨架 / 错误 / 空态正确；歌曲页这次能看到错误
- [ ] 离线 → 回在线，各屏自动恢复
- [ ] 多选进入 / 全选 / 批量删除 / 退出
- [ ] 文件操作（删除 / 改名 / 回收站）正常
- [ ] 转屏后当前页签不丢
- [ ] 杀进程重进，状态恢复正确
- [ ] 下载相关状态不受登出影响（ADR-0007）

## 9. 风险与待确认

| 风险 | 缓解 |
|---|---|
| 大范围重构，回归面广 | 先加兼容层，屏幕逐个迁；每步可编译可跑 |
| 单一 StateFlow 约定被打破 | 保留组合根，只改内部；需要用户明确同意 |
| 每节独立 job 改变取消语义 | 新测试钉死「不同节互不取消」；这是有意修复 |
| 迁移期两份 state 写法并存 | delegating property 只读；禁止新代码写旧字段 |
| 测试文件 2365 行迁移成本 | 先迁加载用例，其余保留；不追求一次性拆完 |

**待确认**
1. 是否接受「单一 StateFlow 内部拆 slice」？推荐接受。
2. `LibraryCache` 是 class 还是继续留在 ViewModel？推荐 class。
3. 是否顺手修 `section` / `playbackMode` 的 saveable 问题？推荐单独 ticket。
4. 是否把 `QQDownloadViewModel` 的 24 字段也按同样方式拆？推荐另开，不要混。

## 10. 新窗口开工提示词

```
按 docs/library-state-refactor.md 执行。先做 Step 0 基线 + 迁移清单（38 个字段的读写位置、
16 个收整个 state 的 Composable 排序），再做 Step 1：新增 SessionState / LibraryState /
DownloadState / SearchState / FileOpsState，把 JellyfinUiState 改成组合根并保留只读
delegating properties。行为不变，测试全绿。完成后报告字段归属表和下一步建议。
```
