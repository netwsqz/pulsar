# 08 — 专辑网格与播放列表/艺术家列表行

**What to build:** 专辑网格每张卡高度一致，短标题不会让底边参差；副标题用次要颜色、只占一行，不跟标题抢注意力。播放列表和艺术家列表行与歌曲行使用同一套语法；艺术家列表和专辑网格的骨架都从顶部开始、带文字区，加载完不瞬移。

**Blocked by:** 01（设计 token 收口与设置页底部留白）、02（删除流派页）、07（歌曲与搜索列表行）。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §5、§11

- [x] 专辑网格每张卡等高；标题最多/固定两行；副标题次要色且只占一行。
- [x] 播放列表列表行和艺术家列表行与歌曲行使用同一套槽位、字号和尾槽。
- [x] 艺术家列表骨架从顶部开始，加载完成后列表不瞬移。
- [x] 专辑网格骨架带标题/副标题文字区，加载完成后行高不跳。
- [ ] 构建通过；真机验证专辑/播放列表/艺术家三屏。

## Comments

### 实现记录（2026-11-01，agent）

**改了什么**

- **专辑网格卡等高**（`AlbumGridCard`）：标题 `minLines = 2, maxLines = 2` 固定两行，短名也占满两行
  高度，2 列网格底边不再参差；副标题改用 `onSurfaceVariant` + `maxLines = 1` + 省略号（spec §5/§11）。
- **专辑卡骨架**（`AlbumCardSkeleton`）：复刻真实卡结构——方形封面 + `SpaceMidMedium` 内边距 +
  **两行标题 + 一行副标题**的文字区，描边/圆角与 `AlbumGridCard` 一致。文字区高度直接取
  `titleMedium` / `bodyMedium` 的 `lineHeight`（经 `LocalDensity` 转 dp），不写死 24/20dp——
  系统字号放大时真实卡与骨架仍一起变高。
- **两处网格骨架共用**（`AlbumGridSkeleton`）：专辑页与艺术家详情页原来各写一份 `LazyVerticalGrid`
  骨架（艺术家详情那份还只有封面方块），现在统一走一个函数，列数/间距/底部留白天然同步。
- **艺术家列表骨架顶部对齐**：`TrackListSkeleton` 补 `Modifier.fillMaxSize()`。外层 Box 是居中对齐，
  wrap-content 骨架原本会竖直居中，加载完跳回顶部。
- **骨架行距与真实列表对齐**：`TrackListSkeleton` 新增 `rowSpacing`，默认 `Dimens.SpaceTiny`，
  与歌曲 / 播放列表 / 艺术家页真实 `LazyColumn` 的 `spacedBy` 一致；专辑详情真实列表无额外间距，
  调用处传 `0.dp`。此前骨架行距比真实行少 4dp，8 行累计约 28dp 的加载后位移。
- **播放列表 / 艺术家行**：工单 07 已把它们迁到 `SearchResultRow` → `MediaRowLayout`/`MediaRowText`，
  本单核对确认与歌曲行同槽位（前槽 48 / 间距 12 / 文字列 weight）、同字号（bodyLarge + bodySmall
  次要色）、尾槽同为 48dp（这两类行无行尾操作，按 spec §5「尾槽 48（可空）」传 null），无需再改。

**验证**

- `:app:compileDebugKotlin`：通过。
- `:app:testDebugUnitTest`：**664 个用例，0 失败**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**（含 arm64-v8a NDK 层）。
- 本单没有新增单测：仓库无 Compose UI 测试设施，spec §「不测什么」明确排除间距/字号/颜色/手势；
  改动全是布局与组合，没有可测的纯函数缝。

**代码审查后修正**

- Spec：骨架行距与真实列表不一致（见上，`rowSpacing`）。
- Standards：①专辑骨架文字条高度改为从排版行高推导，避免字体缩放时再次错开；②两处重复的网格骨架
  抽成 `AlbumGridSkeleton`。
- Standards 提出但未改：`AlbumGridCard` 与 `AlbumCardSkeleton` 的卡片外壳（Card + 描边 + 圆角）
  仍是两处书写。抽共享外壳需要给 `Card` 的点击/非点击两个变体加分支，代码量与 4 行重复相当，
  按「改动范围尽量小」保留现状；两处相邻、注释已标明必须同步。

**真机待验清单**

1. 专辑页：短名/长名卡片底边齐平；标题最多两行；副标题灰色单行、超长省略。
2. 专辑页：加载骨架时卡片与加载完成同高，文字位置不向上跳。
3. 艺术家列表：加载骨架从顶部开始，加载完成不瞬移；行间距与歌曲页一致。
4. 艺术家详情：网格骨架带两行标题 + 一行副标题，加载完不跳。
5. 播放列表页：行高、字号、分隔线与歌曲页一致（无行尾按钮）。
6. 深/浅色各看一遍专辑页与艺术家详情骨架。

