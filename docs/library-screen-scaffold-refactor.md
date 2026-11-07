# LibraryScreenScaffold 重构执行计划

> 状态：**待研究（Speculative）**。本文是 candidate 7（页面骨架收成一个 module）的执行规格，不是 ADR。
> 相关：`docs/offline-policy-refactor.md`（离线提示类型）、`docs/unified-search-refactor.md`（搜索页骨架）。
> 注意：这是 presentation 的 consolidation，收益主要是「少改几处、少漏一个分支」，不直接提高可测性。项目没有 Compose 测试设施，验收靠编译 + 真机。

---

## 1. 目标

把列表页/详情页的同一副骨架收成一个 module：

- **一个 `LibraryScreenScaffold`**：标题 + 离线提示 + 三态（骨架 / 错误 / 空）+ 内容槽。
- **一个 `LoadState`**：三态只实现一次，漏分支在结构上不可能。
- **一个 `TrackList` item 工厂**：五个列表页不再各自拼 8–9 个参数的行。
- **详情头拆成两个变体**：有动作 / 无动作，不再用占位参数凑同一个函数。

## 2. 非目标

- 不改视觉、间距、圆角、玻璃材质；继续用 `Dimens` / `GlassPanel`。
- 不改数据加载、离线、下载、搜索语义。
- 不引入 Compose 测试设施。
- 不重写 `TrackRow` 的交互（多选、长按菜单、播放指示条）。
- 不把 `MiniPlayerBar` / `NowPlayingScreen` 一起拆（candidate 1 的事）。

## 3. 现状证据

| 位置 | 现状 |
|---|---|
| `MainActivity.kt:3146–3172` | `MediaDetailScaffold`，19 个参数；`showActions=false` 时调用方传占位值 |
| `MainActivity.kt:3307–3321` | `AlbumDetailContent`，14 个参数 |
| `MainActivity.kt:3709–3724` | `PlaylistDetailContent`，15 个参数；与专辑详情共享 12 个 |
| `MainActivity.kt:489–528` | 艺术家列表页壳 |
| `MainActivity.kt:3080–3125` | 专辑列表页壳 |
| `MainActivity.kt:3428–3468` | 播放列表页壳 |
| `MainActivity.kt:1784–1830` | 歌曲页壳（**没有 error 分支**） |
| `MainActivity.kt:503–526 / 591–607 / 1809–1817 / 2600–2632 / 3096–3123 / 3384–3412 / 3440–3466 / 3561–3656 / 3770–3808 / 4241–4249` | 三态块 10 处 |
| `MainActivity.kt:1819–1828 / 2580–2589 / 3400–3410 / 3606–3615 / 3797–3806` | TrackRow 组装 5 处 |
| `MainActivity.kt:2707–2724 / 3677–3685 / 4115–4123` | 三个 section header |
| `MainActivity.kt:496–500 / 1789–1798 / 3089–3093 / 3434–3438 / 3848–3852 / 4235–4239` | 六个内联 `titleLarge` |
| `MainActivity.kt:2992–3037 / 3049–3068 / 2802–2831` | 三套骨架形状 |
| `MainActivity.kt:2849–2897 / 2900–2932` | 发现页两张卡几乎相同 |

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 形态 | Composable + `LoadState` sealed；不持有状态 |
| D2 | 三态 | `Loading / Error(message, retry) / Empty(message) / Content` |
| D3 | 详情头 | 拆成 `DetailHeader.WithActions` / `.WithoutActions`，删除占位参数 |
| D4 | 行组装 | `TrackList(tracks, ...)` 内部调 `TrackRow`；调用方只传列表与动作 |
| D5 | 标题 | 一个 `SectionHeader`，三个旧组件做薄委托后删除 |
| D6 | 文案 | 离线/空态文案从 `OfflinePolicy`（candidate 2）取；本方案不新造字 |
| D7 | 迁移顺序 | 先迁最简单的列表页（专辑 / 播放列表），最后迁详情页 |

## 5. 目标形状（sketch）

```kotlin
sealed interface LoadState {
    data object Loading : LoadState
    data class Error(val message: String, val onRetry: (() -> Unit)? = null) : LoadState
    data class Empty(val message: String) : LoadState
    data object Content : LoadState
}

@Composable
fun LibraryScreenScaffold(
    title: String,
    offlineNotice: String?,
    loadState: LoadState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dimens.SpaceMedium)) {
        Text(title, style = MaterialTheme.typography.titleLarge, ...)
        offlineNotice?.let { OfflineNoticeText(it) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (loadState) {
                LoadState.Loading -> TrackListSkeleton(...)
                is LoadState.Error -> ErrorState(loadState.message, onRetry = loadState.onRetry)
                is LoadState.Empty -> EmptyState(loadState.message)
                LoadState.Content -> content()
            }
        }
    }
}
```

```kotlin
@Composable
fun TrackList(
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    coverFor: (Track) -> JellyfinRequest?,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    modifier: Modifier = Modifier,
    leadingNumbers: Boolean = false,
)
```

详情头：
```kotlin
@Composable
fun DetailHeader(
    cover: JellyfinRequest?,
    title: String,
    subtitle: String,
    summary: String,
    actions: DetailActions?,   // null = 无动作
    offlineNotice: String?,
)

data class DetailActions(
    val playLabel: String,
    val playable: Boolean,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val download: (@Composable () -> Unit)?,
)
```
这样 `showActions=false` 不再需要 `playLabel=""` / `playable=false` / `onPlay={}` 这类占位值。

## 6. 执行步骤

### Step 0 — 基线 + 站点清单

1. 跑全量单测 + `assembleDebug`。
2. 把 10 个三态块、5 个 TrackRow 组装点、3 个 header、3 套骨架逐个列出来，标出差异（哪些有 error、哪些有 offline 空态、哪些有 leadingNumber）。
3. 先确认 `TracksContent` 的 error 分支是**漏了**还是有意为之；按现在的代码看是漏了。

**验收**：站点清单 + 差异表；知道先迁哪两个屏。

### Step 1 — `LoadState` + `LibraryScreenScaffold`，迁 2 个简单列表页

1. 新增 `app/src/main/java/com/example/pulsar/ui/components/LibraryScreenScaffold.kt`、`LoadState.kt`。
2. 先迁 `AlbumsContent` 与 `PlaylistsContent`：
   - 标题 / 离线提示 / 三态 / 内容槽；
   - 保留原有 `LazyVerticalGrid` / `LazyColumn` 作为 content。
3. 编译 + 真机看这两页的加载 / 错误 / 空态。

**验收**：两页视觉与交互不变；代码里不再有各自的三态 `when`。

### Step 2 — 迁歌曲页与艺术家页（顺手补 error 分支）

1. `TracksContent` 改用 scaffold；`errorMessage` 现在会显示。
2. `LibraryGroupsContent` 改用 scaffold。
3. 真机断网 / 制造错误，确认歌曲页这次能显示错误 + 重试。

**验收**：歌曲页错误分支出现；其余行为不变。

### Step 3 — 拆详情头，迁详情页

1. `MediaDetailScaffold` 的 header 部分抽成 `DetailHeader`；`showActions` 改成 `actions: DetailActions?`。
2. `AlbumDetailContent` / `PlaylistDetailContent` / `LibraryGroupDetailContent` 改用新 header。
3. 删除占位参数与 `showActions` 分支。
4. 真机看三页的播放 / 随机 / 下载 / 离线隐藏。

**验收**：详情页不再有 `playLabel=""` / `playable=false` / `onPlay={}` 这类调用。

### Step 4 — `TrackList` + `SectionHeader` 收口

1. 五个 TrackRow 组装点改成 `TrackList`（或 `TrackList` 的变体）。
2. 三个 section header + 六个内联 title 收成一个 `SectionHeader`。
3. 发现页两张卡如果只差 click / long-click，抽成一个 `DiscoverCard`（可选）。
4. 删除旧组件与重复字符串。

**验收**：`grep "TrackRow("` 只剩 `TrackList` 内部一处；header 只剩一个定义。

### Step 5 — 清理 + 文档

1. 删除 `MediaDetailScaffold` 的旧分支、未使用的 `fullRow`（如果不再需要）。
2. 更新 `CLAUDE.md` 的 UI 章节：加 `LibraryScreenScaffold` / `LoadState` / `TrackList` 的职责。
3. `CONTEXT.md` 不需要新术语（这是 UI 结构，不是领域概念）。
4. 全量测试 + `assembleDebug` + 真机清单。

## 7. 测试策略

- **没有 Compose 测试**：靠编译 + 真机。
- 如果 `LoadState` 的映射有纯逻辑（例如从 `state + error + empty` 算出 `LoadState`），抽成纯函数并单测。
- 现有 UI 相关纯函数测试（`OfflineVisibleLibraryTest`、`DiscoverSectionsTest`）保留。

## 8. 真机验收清单

- [ ] 歌曲 / 专辑 / 艺术家 / 播放列表四页：加载骨架、错误、空态、内容
- [ ] 歌曲页制造网络错误 → 显示错误 + 重试（改前没有）
- [ ] 专辑详情 / 播放列表详情 / 艺术家组详情：头部、播放、随机、下载、离线隐藏
- [ ] 发现页横滑卡片、每日推荐、随机专辑、三节历史
- [ ] 离线：各页提示与空态
- [ ] 多选条 / 长按菜单 / 播放指示条不受影响
- [ ] 转屏 / 深色模式 / 玻璃材质无变化

## 9. 风险与待确认

| 风险 | 缓解 |
|---|---|
| 抽象过度，四个页面被塞进一个不合适的骨架 | 先迁 2 个简单页；发现差异大就保留变体，不强行统一 |
| 详情头的两种模式差异比想象大 | 用 `DetailActions?` 而不是布尔 + 占位参数；差异大就保留两个 composable |
| 没有 Compose 测试，回归靠肉眼 | 每步只迁 1–2 页，真机看；不一次全改 |
| 文案收口与 OfflinePolicy 重叠 | 等 candidate 2 的 `OfflineNotice` 类型；在那之前先用字符串 |
| `TrackList` 的参数又变成 9 个 | 用 `TrackListActions` 值对象；不要每个回调一个参数 |

**待确认**
1. `LoadState` 是 sealed 还是 enum + 可选参数？推荐 sealed（Error 带 retry）。
2. `LibraryScreenScaffold` 是否要支持 grid / list 两种内容？推荐内容槽由调用方给，scaffold 只管外框。
3. 是否顺手修 `TracksContent` 漏 error 分支？推荐在 Step 2 一起修（这是本方案最直接的收益）。
4. 发现页两张卡是否合并？推荐可选，不阻塞主线。

## 10. 新窗口开工提示词

```
按 docs/library-screen-scaffold-refactor.md 执行。先做 Step 0 站点清单（10 个三态块、5 个
TrackRow 组装点、3 个 header、3 套骨架的差异表），再做 Step 1：新增
ui/components/LibraryScreenScaffold.kt 与 LoadState.kt，先迁 AlbumsContent 与
PlaylistsContent。每步只改 1–2 页，编译 + assembleDebug + 真机看，不要一次全改。
完成后报告差异表与迁移结果。
```
