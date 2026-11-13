# LibraryState 重构执行计划

> 状态：**已完成（2026-11-13，#11–#16）**。本文是 candidate 3（曲库加载与状态收成 LibraryState）的执行规格，不是 ADR；
> 架构决策见 ADR-0010，`QQDownloadViewModel` 的拆分仍是独立的 #31。
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
    /**
     * 直接内嵌 candidate 4 的 DownloadLibrary 快照。
     * 不要在这里重新派生「已下载」——字节 ∩ 名单只允许有一处算法。
     */
    val snapshot: DownloadSnapshot = DownloadSnapshot(),
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

> **2026-11-09 改口径（与 candidate 4 对齐）**：`DownloadState` 原草图里的
> `cachedTrackIds` / `cachedAlbums` / `cacheBytes` / `cacheTrackCount` 会变成**第五份「已下载」派生**，
> 与 `DownloadLibrary` 的「一个真相」直接冲突。candidate 4 已经落地（#4 / #5），
> 所以这里改成内嵌 `DownloadSnapshot`：状态来自 `DownloadLibrary.state`，
> `DownloadState` 只额外持有纯 UI 的删除进度与提示。
> **`cachedAlbums`（已下载专辑列表）也由 DownloadLibrary 提供**，不要在 ViewModel 里重算。

设计要点：
- `LibraryCache.onSectionEntered(section)` 替换 UI 里的 `LaunchedEffect` 分支；UI 只报「进了哪一节」，不再知道 load 方法名、幂等守卫、session ready。
- 每节一个 job；同一节的重复进入取消旧请求，不同节互不取消。
- `null = 没拉到 / 失败`、`emptyList = 拉到但为空` 的语义写在 `LibraryCache` 里；UI 不再逐字段判断。
- `failedSection` 用 enum，不再用字符串 `"recentlyPlayed"`。
- `SessionState.error` / `LibraryState.error` / `FileOpsState.message` 分开；`errorMessage` 不再承载五种语义。

## 6. 执行步骤

### Step 0 — 基线 + 迁移清单

> **已完成 2026-11-11（#11）**：基线全量单测通过（68 个测试类 / 829 项、0 失败）。
> 36 个字段（原文写 38，实际逐个数是 36）的读写位置清单、以及「收整个 state 的 Composable
> 按读字段数排序」都落在 issue #11 的评论里：生产 UI 读点全部在 `MainActivity.kt`（`state.xxx`），
> 写点全部在 `JellyfinViewModel.kt`（`copy(xxx = ...)`）；`selectedAlbum` / `selectedPlaylist`
> 生产 UI 读点为 0（与本节判断一致）。本步未碰生产代码。

1. 跑全量单测。
2. 列出 38 个字段各自的读/写位置（可用 `grep`），标出：
   - 生产 UI 读；
   - 只有 VM 内部读；
   - 只写不读（`selectedAlbum` / `selectedPlaylist` / `playbackTracks`）。
3. 列出 16 个收整个 state 的 Composable，按「读几个字段」排序，先迁最简单的。

**验收**：知道每个字段的归属；知道先迁哪 3 个屏。

### Step 1 — 嵌套 state + delegating properties（不改行为）

> **已完成 2026-11-11（#11）**：新增 `com.example.pulsar.state` 包——
> `SessionState`、`LibraryState`（内含 `SearchState` / `FileOpsState`）、`DownloadsState`；
> `JellyfinUiState` 改成组合根（`session` / `library` / `downloads`），旧 36 个字段的读法保留为
> **只读属性**，写法保留为兼容 `copy(...)`（旧参数名 → 落到对应 slice）。
> 与原文的差异：
> - **旧读法不是 `get()` 自定义 getter，而是构造时求值的 `val`**：自定义 getter 会让
>   `state.errorMessage != null` 之后的智能转换失效（`MainActivity` 有 8 处 `when` 分支），
>   而「现有代码不改」是这一步的验收线。slice 不可变 ⇒ 派生值不会过期。
> - **slice 名用 `DownloadsState` 而不是 `DownloadState`**：后者已被调度器的
>   `com.example.pulsar.DownloadState`（单任务进度）占用。
> - `cachedTrackIds` / `cachedAlbums` 暂放 `DownloadsState`（`visibleTrackIds` / `cachedAlbums`），
>   按 2026-11-09 改口径它们最终来自 `DownloadSnapshot`——本步只搬位置、不换来源。
> - `isOffline` 归 `LibraryState`（说的是「曲库数据来自快照还是服务器」，不是会话凭据）；
>   `isOfflineSearch` 归 `SearchState`；回收站三个字段并入 `FileOpsState`（同一个 PC 伴生服务）。
> - `isLoading` / `errorMessage` 暂放 `SessionState`（与草图一致），它们仍是登录与曲库共用的
>   通道，D4 / D5 拆分留给 #12 / #13。`discoverFailedSections` 仍是 `Set<String>`，
>   枚举化留给 #12。
> - 生产代码只有 `JellyfinViewModel.signOut()` 一行构造方式变了（组合根构造参数不再是旧字段）；
>   `MainActivity.kt` 等 UI 文件**零改动**。
> - **D6 的口径修正**：代码里「下载不清」只指**字节与名单**（ADR-0007）。`DownloadsState` 的
>   **界面字段**现在会随登出 / 过期重置（`signOut` 重建整份 state、`expireSession` 显式清空
>   `cachedTrackIds` / `cachedAlbums` / `isDeletingDownload` / `downloadDeleteMessage`），
>   与改前一致；slice 注释按这个口径写，#13 收口时别把两者混在一起。
>
> 新测试：`JellyfinUiStateShapeTest`（9 项，36 个字段双向钉死）。
> 全量单测 838 项全绿，`assembleDebug` 通过。

1. 新增 `SessionState` / `LibraryState` / `DownloadState` / `SearchState` / `FileOpsState`。
2. `JellyfinUiState` 改成组合根，保留旧字段的 delegating getter（只读）。
3. 所有现有代码不改，编译通过，测试全绿。
4. 补一个 `JellyfinUiStateShapeTest`：旧读法与新 slice 写法的值一致。

**验收**：零行为变化；所有旧代码不用改就能编译。

### Step 2 — 抽 `LibraryCache`（加载 + 每节 job + 三态）

> **已完成 2026-11-12（#12）**：新增 `com.example.pulsar.library.LibraryCache`（`internal`），
> 每节一个槽（专辑 / 发现随机 / 三节历史 / 专辑曲目 / 播放列表 / 播放列表曲目 / 搜索），
> 每槽一份 job + 代次；生产代码 `grep albumsJob` **为 0**（共享句柄已删）。
> 与原文的差异：
> - **三态语义**落在 module 的两条加载路径里（`loadLibrary` 会落快照、`loadHistory` 不落且失败只记节）：
>   成功写服务端给的值（含空列表），失败保持 `null` 并**绝不**写空列表；401 / 断网交给宿主统一处理。
> - **界面只报「进了哪一节」**：`MainActivity` 的加载 `LaunchedEffect` 只剩 `section` 一个 key，
>   调 `viewModel.onSectionEntered(section)`；`sessionReady` / `isOffline` / `historyEpoch` 三个 key 全删。
> - **补跑**：会话就绪（登录 / 恢复成功）与离线翻转（`markOnline` / `goOffline` 返回 true）由
>   `reloadCurrentSection()` 按 VM 记住的 `currentSection` 重放「进了这节」。**断网启动（快照兜底）
>   那条路径不补跑**：会话虽然就绪，但离线时重发只会白白失败（改前界面 key 变化会发一轮注定失败的请求）。
> - **离线本地清单路径**（播放列表 / 播放列表曲目）仍实现在 VM，但由 module 按节调起（Host 回调），
>   因此也享受「同节取消旧请求」。**搜索**的防抖 / job / 代次也收进 module；`updateSearchQuery` 只写查询词。
> - `discoverFailedSections` 从 `Set<String>` 换成 `Set<LibrarySectionKey>` 枚举（界面 3 处 + 测试同步）。
> - `isLoading` / `errorMessage` **仍是登录与曲库共用的单一通道**（与 #11 口径一致）。
>   `isLoading` 改成**按在途节计数**：多节同时在途时，后台那一节先落地不会把圈收掉
>   （否则当前屏会从骨架闪一下空态）。`errorMessage` 的跨节串扰仍留给 #13 的类型化错误。
> - `JellyfinViewModel` 的 `loadX` 保留为「失败重试」入口（委托 module）；`refreshDiscoverAlbums` 删掉
>   （无调用方，换一批走 `refreshDiscover()`）。
> - **会话边界**：记住的「当前分区」属于本会话，登录 / 登出 / 过期时清空——登录页与主页面是
>   二选一组合，主页面重建时界面会重新上报；留着旧值会把上个会话的详情页重放进新会话。
> - **并发收口**：`refreshDiscover` 的刷新圈以「槽里当前那份 job」为准（切歌 / 离线翻转 / 二次刷新
>   会替换 job，抓旧引用会提前收圈）；`goOffline` 的「是否翻转」在写之前一刻判定（并发失败只算一次）；
>   `persistSnapshot` 加锁串行（多节同时成功时防止旧快照最后写盘）。
> - **回退**：删除 `app/src/main/java/com/example/pulsar/library/LibraryCache.kt` 与对应测试，
>   `git revert` 本 commit 即可回到共享 `albumsJob`（无数据迁移、无落盘格式变化）。
>
> 验证：全量单测 70 个类 / 870 项全绿（基线 838，新增 `LibraryCacheTest` 29 项 + VM 集成 3 项）；
> `assembleDebug` 通过。真机项见 issue #12 的「待真机确认」评论。

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

> **已完成 2026-11-12（#13）**：三个 slice 各自拥有清理方法，`JellyfinUiState` 加组合入口
> `cleared(keepLoginForm, errorMessage)`；`signOut` / `expireSession` 共用一条 `resetSession()`
> 拆除路径，状态侧只剩一行调用。与原文的差异：
> - **命名**：用 `cleared()` 而不是 `clear()` / `clearCaches()`——返回一份清好的新实例
>   （slice 不可变），不是原地修改；`LibraryState.clearCaches()` 的「caches」也不准确
>   （多选、文件操作提示不是缓存），所以用 `cleared()` + `clearSnapshotBackedScreens()`。
> - **实现口径**：每个 `cleared()` 直接返回默认实例（`SessionState.cleared()` 保留
>   `serverUrlHistory`，`keepLoginForm` 时另留 `serverUrl` / `username`）。约定
>   **「字段默认值 = 已清」**，新增字段自动跟上，不需要改动清理代码。
> - **`DownloadsState` 的界面字段照旧随登出 / 过期清**（与改前一致）。ADR-0007 的
>   「下载不清」指**字节与名单**，它们归 `DownloadLibrary` / `MediaDownloads` / `DownloadLedger`，
>   不在 `JellyfinUiState` 里，清理方法碰不到。这一条把 #11 留下的口径问号收掉了。
> - **`expireSession` 现在与 `signOut` 完全同口径**：多清了 `historyEpoch` 与回收站字段
>   （改前例外保留）。两者都是「这个会话看到的东西」，且回收站列表进设置页会重拉、
>   `LibraryCache.invalidate()` 本就把历史版本号记为 -1，无副作用。
> - **`login()` 也收进同一套**（原文没提）：发起新登录时旧账号的界面数据一律作废，
>   改前手抄 25 个字段；现在 `library.cleared()` + `downloads.cleared()`。
> - **恢复在线**：`goOnline` / `markOnline` 改为 `library.clearSnapshotBackedScreens()`
>   （清六屏 + `discoverFailedSections` + `isOffline` + `isOfflineSearch`），
>   `downloads.visibleTrackIds` 归零。`isDiscoverRefreshing` **不清**（#12：由 `refreshDiscover`
>   自己收）；`goOnline` 保留原先「整页重来顺手收刷新圈」的行为。
>
> 新测试：`StateClearedTest`（6 项）——夹具覆盖每个 slice 的每个字段（反射递归），
> 新增字段没进夹具会红；`JellyfinViewModelTest` 补 2 条集成测试（登出 / 过期分别清回默认）。
> 全量单测 71 个类 / 878 项全绿（基线 870，新增 8 项）；`assembleDebug` 通过。

1. `SessionState.clear()`：只清会话/表单/错误。
2. `LibraryState.clearCaches()`：清曲库/发现/详情/搜索/多选/文件操作。
3. `signOut()` / `expireSession()` 改为调这两个方法 + 保留 `serverUrlHistory`；不再手抄字段。
4. `goOnline` / `markOnline` 改为 `library.clearSnapshotBackedScreens()`。
5. 补测试：新增一个字段后，两个 reset 路径都自动清（用反射或显式测试字段集）。

**验收**：`expireSession` / `signOut` 里不再出现 20+ 行字段列表；新增字段不会漏清。

### Step 4 — 屏幕逐个迁到 slice

> **前半已完成 2026-11-12（#14）**：四个列表页只收自己需要的 slice，不再收整个 `JellyfinUiState`。
> - `AlbumsContent` / `LibraryGroupsContent` → `SessionState`（加载 / 错误通道）；列表仍由调用方按离线策略滤好后传入。
> - `PlaylistsContent` → `LibraryState`（`playlists` / `isOffline`）+ `SessionState`。
> - `TracksContent` → `LibraryState`（`tracks` 判断骨架 vs 空态）；可见列表仍由调用方传入。
> - 三态条件与改前逐字一致，零行为变化；`MainActivity` 旧兼容读点 **130 → 118**（-12）。
> - **`TracksContent` 仍没有错误分支**（原文 §3 的已知缺口），留给页面骨架工单 #25 补，这次只换 slice。
> - 未新增单测：项目没有 Compose 测试设施，UI 迁移靠编译 + 真机（`CLAUDE.md` 口径）。
> - 剩余读点归 #15（详情 / 设置 / 搜索 / 已缓存 / 回收站）与 #16（父层与登录页，以及删兼容层）。
>
> 后半（详情页 / 设置页）留给 #15。

> **后半已完成 2026-11-12（#15）**：详情 / 搜索 / 设置 / 已下载列表 / 回收站各页只收自己的 slice，
> 不再收整个 `JellyfinUiState`。
> - `AlbumDetailContent` → `SessionState`（`serverUrl` / 加载 / 错误）。
> - `PlaylistDetailContent` → `LibraryState` + `SessionState`；`isOffline` 不再由调用方传参，页内读
>   `library.isOffline`（调用点少一处旧读法）。
> - `LibraryGroupDetailContent` → `LibraryState`（摘要里的服务端口径 `albums`）+ `SessionState`。
> - `SearchContent` → `SearchState`（`query` / `isSearching`）+ `SessionState`（错误通道）；
>   `results` 仍由调用方按离线策略滤好后传入。重试回调改读 `state.library.search.query`。
> - `SettingsContent` / `RecycleBinSection` → `FileOpsState`；`SettingsContent` 从此不再收 `state`。
> - `CachedCopiesContent` → `DownloadsState`（`cachedAlbums` / `isDeleting`）。
> - `DownloadsSection` 本就从 `DownloadLibrary.state` 读（#7），无旧读法，未改动。
> - 三态条件与改前逐字一致，零行为变化；`MainActivity` 旧兼容读点 **118 → 75**（-43）。
> - 剩余 75 处全归 #16：`MusicLibraryScreen` 父层 29、`DiscoverContent` 17、`TrackFileOpsDialogs` 13、
>   `LoginScreen` 9、`PulsarApp` 7。`TrackFileOpsDialogs`（删除 / 改名对话框）仍读旧字段，
>   与父层一起留给 #16——这次只迁 issue 列出的八块。
> - 未新增单测：项目没有 Compose 测试设施，纯类型层迁移、无逻辑变化（`CLAUDE.md` 口径）。
> - 全量单测 71 个类 / 878 项全绿，`assembleDebug` 通过。真机项见 issue #15 的「待真机确认」评论。

按 Step 0 的排序，一次迁 2–3 个：
1. `AlbumsContent` / `PlaylistsContent` / `LibraryGroupsContent`（只读 `isLoading` / `error` / 列表）。
2. `TracksContent` / `DiscoverContent`（读 loading / error / 离线相关）。
3. `AlbumDetailContent` / `PlaylistDetailContent` / `SearchContent`。
4. `SettingsContent` / `CachedCopiesContent` / `RecycleBinSection` / `DownloadsSection`。
每个小步都编译 + 真机看一眼；不要求一次迁完。

**验收**：每迁一个屏，旧 delegating property 的读点减少；没有新 bug。

### Step 5 — 删除死字段与旧兼容层

> **已完成 2026-11-13（#16）**：迁移期兼容层整段删除，`JellyfinUiState` 只剩 `session` / `library` /
> `downloads` 三个字段，界面与测试全部改读 slice。死字段逐项确认后处理：
> - **`selectedAlbum` / `selectedPlaylist` 删除**。查证结论：生产 UI 读点为 0，导航真相一直是
>   `LibrarySection.AlbumDetail(album)` / `PlaylistDetail(playlist)`；它们只服务「同 id 不重复拉」
>   的幂等守卫与断网时的专辑曲目快照兜底。守卫状态搬进 `LibraryCache.currentAlbum` /
>   `currentPlaylist`（`internal`，会话切换时随 `invalidate()` 作废），**不是漏接线**。
> - **`batchDeleteTargets` 删除**。声明后从未读写；真正的批量删除目标是 `deleteTarget`（由多选条
>   按当前列表填）。属于早期实现的残留。
> - **`playbackTracks` 保留**。它仍有生产读者（`knownTracks()` 要把播放队列里的歌纳入离线可见性），
>   不是死字段；改成 `internal` 并在 KDoc 里写明「界面不读，只给 ViewModel 与单测」。
> - **`section` / `detailOrigin` 改 `rememberSaveable`**，存进 `LibrarySectionSaver`
>   （`LibrarySection.kt`，JSON 字符串；`Playlist` 为此加 `@Serializable`）。转屏 / Activity 重建后
>   当前页签与详情返回路径都不丢；`LibrarySectionSaverTest` 覆盖全部 12 个分区变体。
> - **界面读点全部迁完**：`MusicLibraryScreen` 父层、`DiscoverContent`、`TrackFileOpsDialogs`、
>   `LoginScreen`、`PulsarApp`；`PulsarApp` 只给三个子页面传 slice，不再传整份 state。
> - **测试**：`JellyfinUiStateShapeTest`（9 项，钉兼容层）删除，换成 `JellyfinUiStateTest`
>   （2 项：组合根只有三块 slice + 默认实例）；`StateClearedTest` 夹具去掉两个字段；
>   `LibraryCacheTest` 改断言守卫状态并补「退出详情再进同一张专辑会重拉」。
>
> 验证：全量单测 **879 项全绿**（基线 878：删 9 + 增 10），`assembleDebug` 通过。
> 真机项见 §8。

1. 删除 `selectedAlbum` / `selectedPlaylist`（如果确认没有 UI 读）。
2. `playbackTracks` 若仍无生产读者，删除或标 `@VisibleForTesting`。
3. 删除 `batchDeleteTargets`。
4. `section` / `playbackMode` 改 `rememberSaveable`（或跟随 candidate 1 的 session 持有方案）。
5. 删除 delegating properties；所有屏只读 slice。
6. `errorMessage` 的旧读点全部迁到类型化 error。

**验收**：`grep selectedAlbum` 只剩 `LibrarySection.AlbumDetail`；`JellyfinUiState` 不再有 38 个顶层字段。

### Step 6 — 清理 + 文档

> **已完成 2026-11-13（#16）**：
> - `CLAUDE.md` 的「界面状态与曲库加载」按新形状重写：组合根只有三块 slice、屏幕直接读写
>   slice、「当前选中」不在 UI 状态里、导航状态用 `LibrarySectionSaver`；ADR 列表更新到 0010。
> - `CONTEXT.md` 新增「状态与数据流」一节：界面状态（JellyfinUiState）/ 会话状态（SessionState）/
>   曲库状态（LibraryState）/ 下载状态（DownloadsState）。
> - 新增 **ADR-0010**：state 按所有权分 slice，组合后仍单一 StateFlow（含理由与后果）。
> - 全量单测 + `assembleDebug` 通过；§8 真机清单待用户逐项确认。

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
5. `DownloadState` 内嵌 `DownloadSnapshot`（本文 2026-11-09 的改法）是否接受？推荐接受——
   否则 candidate 4 的「一个真相」会被这项重构重新拆成两份。

## 10. 新窗口开工提示词

```
按 docs/library-state-refactor.md 执行。先做 Step 0 基线 + 迁移清单（38 个字段的读写位置、
16 个收整个 state 的 Composable 排序），再做 Step 1：新增 SessionState / LibraryState /
DownloadState / SearchState / FileOpsState，把 JellyfinUiState 改成组合根并保留只读
delegating properties。行为不变，测试全绿。完成后报告字段归属表和下一步建议。
```
