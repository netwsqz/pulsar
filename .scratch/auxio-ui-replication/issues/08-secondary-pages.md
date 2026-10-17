# 08 — 专属页视觉底座对齐

**What to build:** 让专属页（搜索 / 设置 / 已缓存列表）自动继承 01 建立的全局视觉底座，保证全 App 切换页面时没有拼接感、不跳。

**Blocked by:** 01（设计 token 底座）

**Status:** ready-for-human（代码完成，真机肉眼验证待做）

- [x] 搜索页视觉对齐新底座（间距/圆角/列表项）
- [x] 设置页视觉对齐新底座（卡片/分组/间距）
- [x] 已缓存列表页视觉对齐新底座
- [x] 三页功能全部不回归
- [x] 全 App 来回切换页面时无明显视觉断层
- [ ] 真机肉眼验证

## Comments

- **共享卡片**：把 QQ 下载页私有的 `QQCard` 抽成 `ui/components/SurfaceCard.kt`，设置页的三组、已缓存列表的专辑行都改用它。三页 + QQ 下载页从此是同一套「低饱和表面 + 细边 + 16dp 圆角」卡片语言；`QQCard` 删除，没有留重复实现。
- **搜索页**：外边距/间距/搜索框圆角与 QQ 下载页同档（16dp、12dp）；分区标题改成与 QQ 下载页区块标题同语言（`titleSmall` + SemiBold + 次要色，原来是品牌色）；`SearchResultRow` 与 `TrackRow` 统一成「48dp 封面位 + 12dp + 主副文本 + 分隔线从封面右缘起」。
- **设置页**：分成「播放」「存储」两组，各带小标题；播放开关收一张卡，回收站、手机缓存各一张卡。控件与回调一行未动，只换容器和间距。回收站/缓存区块原来自带的 24dp 上下外边距改由卡片内边距承担，避免双份留白。
- **已缓存页**：标题/外边距走 token；专辑行由「Material `Card` + 12dp 圆角」改成 `SurfaceCard`（16dp），封面位 64dp 走 `TouchTargetLarge`，列表间距 8dp。
- **TrackRow 也一起对齐**：搜索页、专辑详情、播放列表详情、歌曲页共用这一个行组件，不一起改就还是「同页两套行高节奏」。只改尺寸取值（6→8、48→`TouchTargetSmall`、60→`TouchTargetSmall + SpaceMidMedium`），行为不变。
- **review 修复**：① 设置页补分组标题；② `SurfaceCard` 的可点卡补 `Role.Button` 语义（读屏）；③ `TrackRow` 的间距 token 化补齐。
- **范围**：只动布局与样式（`MainActivity.kt` 的三个专属页 + 共享行组件、`QQDownloadScreen.kt` 的卡片引用、新增 `SurfaceCard.kt`）；ViewModel / 数据层 / 业务回调零改动。
- **测试**：纯布局改动，无 Compose UI 测试设施（项目惯例真机肉眼验证）。`compileDebugKotlin`、`testDebugUnitTest`（555 项）、`assembleDebug` 均通过。
- **未做**：真机肉眼验证（需要用户本人看屏）；共享的 `EmptyState`/`ErrorState` 内部间距仍是字面量——它们不属于本工单三页，改动会波及全 App，留给后续统一。
