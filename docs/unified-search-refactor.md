# UnifiedSearchView 重构执行计划

> 状态：**待研究**。本文是 candidate 5（合并搜索视图收成一个纯 module）的执行规格，不是 ADR。
> 相关：`docs/offline-policy-refactor.md`（离线搜索输入）、`docs/library-state-refactor.md`（SearchState slice）。
> ADR 边界：**ADR-0006 明确决定「不新建聚合 ViewModel；两个 ViewModel 各跑各的 300ms 防抖」**。本方案只抽纯视图状态 + 回调值对象，**不动双 ViewModel / 双防抖结构**。

---

## 1. 目标

把 ADR-0006 的合并规则从 200 行 Composable 里拿出来：

- **一个纯函数**：`unifiedSearchViewState(library, qq) -> SearchViewState`。
- **一个 sealed 输出**：Blank / Loading / Results / LibraryEmptyQqError / BothEmpty / OfflineEmpty。
- **一个回调值对象**：`SearchActions`，收拢 13 个 QQ 回调 + 5 个曲库回调。
- **SearchContent 的 interface 从 30 个参数缩到 3 个**：`state` / `actions` / `modifier`。

## 2. 非目标

- 不新建聚合 ViewModel（ADR-0006 明确否掉）。
- 不改两个 ViewModel 的 300ms 防抖。
- 不改 QQ 下载动作（入队、选曲、重下载）的语义。
- 不改搜索结果排序（曲库永远在 QQ 之前）。
- 不改下载完成后的刷新策略（ADR-0006：不自动刷新）。
- 不改 UI 视觉。

## 3. 现状证据

| 位置 | 现状 |
|---|---|
| `MainActivity.kt:3477–3673` | `SearchContent`，30 个参数；其中 13 个是 `qqViewModel::` 逐条转发 |
| `MainActivity.kt:1205–1240` | 调用点 36 行参数块，`viewModel::*` / `qqViewModel::*` 一行一个 |
| `MainActivity.kt:3553–3595` | 合并规则内联：`libraryEmpty` / `bothEmpty` / 10 分支 `when` |
| `MainActivity.kt:3661–3672` | `QQSearchDialogs` 10 个参数，从 SearchContent 再转发 |
| `QQDownloadViewModel.kt:112–171` | QQ 搜索：先搜歌曲，失败就不再搜专辑；代次防护 |
| `QQDownloadViewModel.kt:628–694` | `QQDownloadUiState` 24 个字段；`searchStatus` 派生 |
| `JellyfinViewModel.kt:770–836` | 曲库搜索：300ms 防抖、离线走快照、代次 |
| `JellyfinUiState.kt:58–60` | `searchQuery` / `searchResults` / `isSearching` |
| `JellyfinUiState.kt:91` | `isOfflineSearch` |
| ADR-0006 | 曲库空、QQ 有结果时显示「曲库中没找到」；QQ 失败只降级，不打断曲库 |

关键规则（目前只存在于 UI 代码里，没有测试）：
- `libraryEmpty = results == null || (tracks/albums/playlists 全空)`
- `bothEmpty = libraryEmpty && !qqHasResults`
- 错误优先级：曲库错误 > QQ 状态 > 离线空 > 普通空
- 离线搜索：只搜已下载；文案要标明「离线搜索」
- QQ 有结果时，曲库侧的问题不能把 QQ 结果盖掉

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 形态 | 纯函数 + sealed `SearchViewState`，不做 IO、不持有状态 |
| D2 | 输入 | 窄输入 `SearchInput` + `QqSearchInput`，不直接吃两个 30 字段 UiState |
| D3 | 输出 | `SearchViewState` 包含结果、空态、错误优先级、离线标记、QQ 状态行 |
| D4 | 回调 | `SearchActions` data class；内部再分 `LibraryActions` / `QqActions` / `DialogActions` |
| D5 | 双 ViewModel | 保持原样；`SearchActions.onQueryChange` 仍然同时调两个 VM |
| D6 | 弹窗 | 留在 `SearchContent`，但通过 `SearchActions` 收参；不塞进纯函数 |
| D7 | 测试 | 新增 `UnifiedSearchViewTest`，覆盖 10 个分支；不写 Compose 测试 |

## 5. 目标形状（sketch）

```kotlin
data class SearchInput(
    val query: String,
    val results: SearchResults?,
    val isSearching: Boolean,
    val isOfflineSearch: Boolean,
    val errorMessage: String?,
    val isOfflineFiltered: Boolean,
)

data class QqSearchInput(
    val songs: List<QQSongDto>,
    val albums: List<QQAlbumDto>,
    val isSearching: Boolean,
    val searchStatus: String?,      // 现有的 searchError ?: credentialExpired
    val hasResults: Boolean,
)

sealed interface SearchViewState {
    data object Blank : SearchViewState
    data object Loading : SearchViewState
    data class Results(
        val library: SearchResults,
        val qqSongs: List<QQSongDto>,
        val qqAlbums: List<QQAlbumDto>,
        val libraryEmpty: Boolean,
        val qqStatus: String?,
        val isOfflineSearch: Boolean,
    ) : SearchViewState
    data class LibraryEmptyQqError(val query: String, val qqStatus: String) : SearchViewState
    data class BothEmpty(val query: String, val isOfflineSearch: Boolean) : SearchViewState
    data class OfflineEmpty(val query: String) : SearchViewState
}

fun unifiedSearchViewState(input: SearchInput, qq: QqSearchInput): SearchViewState
```

```kotlin
data class SearchActions(
    val onQueryChange: (String) -> Unit,
    val onRetryLibrary: () -> Unit,
    val onRetryQq: () -> Unit,
    val onRetryCredential: () -> Unit,
    val onPlayTrack: (List<Track>, String) -> Unit,
    val onAddToQueue: (Track) -> Unit,
    val onOpenAlbum: (Album) -> Unit,
    val onOpenPlaylist: (Playlist) -> Unit,
    val onDownloadSong: (QQSongDto) -> Unit,
    val onRequestRedownload: (QQSongDto) -> Unit,
    val onConfirmRedownload: () -> Unit,
    val onDismissRedownload: () -> Unit,
    val onDownloadAlbum: (QQAlbumDto) -> Unit,
    val onSelectAlbumTracks: (QQAlbumDto) -> Unit,
    val onToggleAlbumTrack: (String) -> Unit,
    val onSelectAllAlbumTracks: () -> Unit,
    val onClearAlbumSelection: () -> Unit,
    val onConfirmAlbumDownload: () -> Unit,
    val onRetryAlbumTracks: () -> Unit,
    val onDismissAlbumPicker: () -> Unit,
)
```

`SearchContent` 签名：
```kotlin
@Composable
fun SearchContent(
    state: SearchViewState,
    query: String,
    actions: SearchActions,
    modifier: Modifier = Modifier,
)
```
（`query` 单独传是因为输入框需要即时值；也可以放进 `SearchViewState.Blank/Results`，二选一。）

## 6. 执行步骤

### Step 0 — 基线 + 分支清单

1. 跑全量单测。
2. 把 `SearchContent` 的 `when` 分支逐条抄成一张表（条件 → 界面），作为测试用例清单。
3. 确认 `QQSearchDialogs` 的 10 个参数是否都能放进 `SearchActions`。

**验收**：10 个分支都有名字；知道哪些分支目前没有测试。

### Step 1 — 纯函数 + 测试（不接 UI）

1. 新增 `app/src/main/java/com/example/pulsar/search/UnifiedSearchView.kt`、`SearchModels.kt`。
2. 先写 `UnifiedSearchViewTest`：
   - 空 query → Blank
   - 曲库搜索中 → Loading
   - 曲库空 + QQ 搜索中 → Loading
   - 曲库有结果 → Results（QQ 结果保留）
   - 曲库空 + QQ 有结果 → Results(libraryEmpty=true)
   - 曲库空 + QQ 错误 → LibraryEmptyQqError
   - 两边全空 → BothEmpty
   - 离线搜索 + 无结果 → OfflineEmpty
   - 离线搜索 + 有结果 → Results(isOfflineSearch=true)
   - 曲库错误 + QQ 有结果 → Results（QQ 结果不被盖掉）
3. GREEN：实现纯函数。

**验收**：10 个用例全绿；函数不依赖 Android/Compose。

### Step 2 — SearchContent 接纯状态（参数先不缩）

1. `SearchContent` 内部把 `when` 换成 `when (state: SearchViewState)`。
2. 保持 30 个参数不变，先让渲染逻辑走新状态；行为不变。
3. 跑测试 + 真机搜索几种情况。

**验收**：搜索结果、空态、错误优先级与改前一致。

### Step 3 — 引入 `SearchActions`，缩 interface

1. 在调用点构造 `SearchActions`（一次性），传给 `SearchContent`。
2. `SearchContent` 只保留 `state` / `query` / `actions` / `modifier`。
3. `QQSearchDialogs` 改成收 `actions` 或它的子集，不再 10 个参数。
4. 删除调用点的 36 行参数块。

**验收**：`SearchContent` 参数 ≤ 4；调用点 ≤ 15 行；编译通过。

### Step 4 — 清理 + 文档

1. 删除 `SearchContent` 里内联的合并判断与重复文案。
2. 更新 `CLAUDE.md` 搜索章节：加 `UnifiedSearchView` 的职责与「两个 ViewModel 不动」。
3. `CONTEXT.md` 已有「合并搜索」，可补一句「合并规则由 `unifiedSearchViewState` 单点决定」。
4. 全量测试 + `assembleDebug` + 真机清单。

## 7. 测试策略

- `UnifiedSearchViewTest` 是主测试面，覆盖 10 个分支。
- 现有 `JellyfinViewModelTest` 的搜索防抖/离线搜索用例保留。
- 现有 `QQDownloadViewModelTest` 的搜索防抖/凭证过期用例保留。
- 不新增 Compose 测试。

## 8. 真机验收清单

- [ ] 空输入 → 「输入关键词搜索音乐库」
- [ ] 曲库有结果 → 歌曲/专辑/播放列表三节，QQ 分区在后
- [ ] 曲库空、QQ 有结果 → 显示「曲库中没找到「X」」+ QQ 结果
- [ ] 曲库空、QQ 连不上 → QQ 分区一行小字 + 重试，曲库结果不被盖
- [ ] 两边全空 → 「未找到相关内容」
- [ ] 离线搜索有结果 → 标明「离线搜索」
- [ ] 离线搜索无结果 → 专用空态
- [ ] 凭证过期 → QQ 分区常驻提示 + 重试
- [ ] 点 QQ 歌曲下载 / 专辑选曲 / 整张下载 → 弹窗正常
- [ ] 搜索框清除 → 立即清空两边结果

## 9. 风险与待确认

> **命名对齐（2026-11-09）**：candidate 2 的 `OfflineView` 里这个字段叫 `isFiltering`，
> 本文草图写的是 `isOfflineFiltered`。以 candidate 2 为准，落地时别造第二个名字。

| 风险 | 缓解 |
|---|---|
| 纯函数吃整个 UiState，变成另一个大 interface | 用窄输入 `SearchInput` / `QqSearchInput` |
| `SearchActions` 变成 20 个 lambda 的新浅 interface | 分组（Library / Qq / Dialog）；只传 SearchContent 真正用到的 |
| 分支迁移时改变错误优先级 | 先写 10 个测试钉死现有行为，再重构 |
| 两个防抖仍各跑各的 | 这是 ADR-0006 的决定，不碰；只在文档里写明 |
| 离线搜索过滤在 VM 和 UI 各做一次 | 由 `OfflinePolicy`（candidate 2）统一；本方案只消费 `isOfflineFiltered` |

**待确认**
1. `query` 是单独参数还是放进 `SearchViewState`？推荐单独，输入框需要即时值。
2. `SearchActions` 用 data class 还是 interface？推荐 data class（Compose 稳定性好）。
3. `QQSearchDialogs` 是否一起搬进 `SearchContent` 的 `actions`？推荐是。
4. 是否顺手把 `SearchContent` 的 QQ 分区拆成独立 composable？推荐是，但另一步。

## 10. 新窗口开工提示词

```
按 docs/unified-search-refactor.md 执行。先做 Step 0 分支清单，再做 Step 1：
新增 search/UnifiedSearchView.kt 与 SearchModels.kt，先写 UnifiedSearchViewTest 覆盖
10 个分支（Blank / Loading / Results / LibraryEmptyQqError / BothEmpty / OfflineEmpty /
离线有结果 / 曲库错误但 QQ 有结果），再实现纯函数。不要动两个 ViewModel 和防抖。
完成后跑全量单测 + assembleDebug，报告分支覆盖与验证结果。
```
