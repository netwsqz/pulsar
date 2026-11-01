# Pulsar 前端显示大排查报告

> 生成时间：2026-11-01
> 输入：前置排查的 75 条问题记录、逐条复核备注、范围决策与重设计方案。
> 行号基准：当前工作树快照（`MainActivity.kt` 约 4870 行）。实施后行号会漂移，定位应以组件名作为定位依据。

> **规格修正（2026-11-01）**：第 3.3 节 #22 与第 4.3.7 节原本建议「三节历史过滤后继续显示」；按 ADR-0008 决定 5，正确做法是**离线时整个隐藏**，不扩展 `VisibleLibrary`。第 5.1 节的「顶层 7 项变 6 项」是旧口径，当前实际是 **6 → 5**（QQ 音乐导入已不在顶层）。以同目录 `spec.md` 为准。

## 1. 范围与方法

### 1.1 范围

- 主流程：发现 / 歌曲 / 专辑 / 艺术家 / 播放列表，以及各详情页、共享外壳（玻璃岛、迷你播放条、展开面板、Snackbar、音量浮窗、多选条）、封面、下载按钮。
- 包含当前未提交的离线态与下载按钮改动。
- 第二批只列问题、不排期：设置 / 搜索 / QQ 导入。
- 流派页已确认删除，影响面与步骤单列第 5 节。

### 1.2 方法

- 以前置排查的 75 条记录、`verify_note` 与重设计方案为输入，按屏归并、去重、统一严重度与置信度标准。
- `high` 条目沿用前置复核结论，复核手段包括：根布局/主题链路静态阅读（#0）、material3 1.4.0 AAR 字节码确认 `LocalContentColor` 默认值（#0）、离线状态流转追踪（#22）、重复渲染路径追踪（#32/#44）。
- `unverified` 条目是静态阅读可推断、但未在真机或运行时确认的问题，表中统一标「待真机确认」。
- 项目当前没有 Compose UI 测试设施，样式结论最终以真机肉眼为准；本报告不把静态推断当成已验收事实。

### 1.3 盲区

- 字体缩放（大字体 / 显示大小）未覆盖。
- 横屏未覆盖。
- 全屏播放页（`NowPlayingScreen.kt`）未覆盖。
- 登录页仅 #61 的间距 token 一项，未做完整排查。
- 手势导航与三键导航的 inset 差异未逐台验证。
- 深色 / 浅色只做了代码级判断，未做真机对照。
- 第二批的设置 / 搜索 / QQ 导入只做清单，不做方案与排期。

### 1.4 计数与置信度标准

- 原始 75 条：P0 1、P1 2、P2 43、P3 29。
- 合并 6 条同源重复记录：**#56/#69 → #5、#57 → #29、#66 → #32、#67 → #31、#73 → #38**；去重后 69 条。
- 主流程 63 条（P0 1、P1 1、P2 37、P3 24）；第二批 12 条（P1 1、P2 6、P3 5）。
- 高置信 7 条：**#0、#1、#12、#22、#32、#44、#45**。
- 未复核 62 条（去重后按行计），全部标「待真机确认」。

## 2. 结论摘要

这次排查的核心不是 75 个互不相关的 bug，而是 6 条根因的重复表现：玻璃只有绘制、没有前景色；浮层没有统一几何与锚点；列表没有统一行语法；详情没有统一页面骨架；离线派生只覆盖顶层列表；下载/动效缺少“未知/停止”态。先修根因，能一次消掉大量表面问题。

### 2.1 问题数量

| 统计标准 | 合计 | P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- |
| 原始记录 | 75 | 1 | 2 | 43 | 29 |
| 主流程（原始） | 63 | 1 | 1 | 37 | 24 |
| 第二批（原始） | 12 | 0 | 1 | 6 | 5 |
| 去重后列出的行 | 69 | 1 | 2 | 39 | 27 |

> 去重后严重度按合并行的较高值计：例如 #31（原 P3）并入 #67（P2）后按 P2 列。

### 2.2 最严重的 5 条

| 排名 | 严重度 | 位置 | 问题 | 为什么排前面 |
| --- | --- | --- | --- | --- |
| 1 | P0 | `MainActivity.kt:4082` | **#0 深色主题下迷你条与玻璃岛的文字/图标仍是默认黑色** | 不是单个控件写错，而是整个外壳没有内容色来源；浅色下恰好能看，深色下迷你条、展开面板、玻璃岛、音量数字全部不可读。一处修复覆盖全局。 |
| 2 | P1 | `MainActivity.kt:2215` | **#22 离线时发现页三节历史未过滤，仍显示未下载内容** | 直接违背 ADR-0008；点未下载的歌只弹提示，点未下载专辑进空详情，且 `hasAnySection` 还会压制整页空态。 |
| 3 | P1 | `MainActivity.kt:3328` | **#44 设置页无底部留白，末张下载卡被迷你条遮挡** | 遮挡区同时拦截点击，用户点不到“已下载”；属于功能性阻断，不是纯观感。 |
| 4 | P2 | `MainActivity.kt:1302` | **#1 Snackbar 未计底部导航 inset，弹出时压住迷你条** | 全 App 所有 Snackbar 都受影响；手势导航约 24dp、三键约 48dp 时固定 80dp 底距必然落进 64dp 高的播放条。 |
| 5 | P2 | `MainActivity.kt:1949` | **#12 长按菜单自绘“玻璃”，浅色白描边白底不可见** | 高置信、浅色主题必现；同时是唯一绕开 `LiquidGlass` 唯一出处的玻璃面，后续会持续制造不一致。 |

紧随其后的高置信项是 **#32 播放列表详情重复头部骨架** 与 **#45 配色色块 36dp 触摸目标**。

### 2.3 建议实施顺序

1. **P0 内容色（#0）**：先加 `GlassPanel` / `LocalContentColor`，一次覆盖玻璃岛、迷你条、展开面板、音量浮窗、长按菜单。不要逐个 Icon 补 tint。
2. **浮层几何（#1、#11、#44、#20/#62/#63）**：Snackbar、音量浮窗、多选条、底部留白统一锚点与 token；#44 虽在第二批，因是 P1 应提前到这一步。
3. **离线规则（#22、#28、#36、#43）**：先写 `OfflineVisibleLibraryTest` 失败用例，再实现派生过滤与详情计数。
4. **列表与详情统一（#32/#31/#29/#33/#34/#68/#37）**：先修播放列表的重复头部与 maxLines，再抽共享行/详情骨架，最后迁专辑与组详情。
5. **迷你条减法与触摸目标（#2、#5/#56/#69、#74、#6）**：同批处理长按菜单/改名弹窗（#12、#18）与 #45 的 48dp 热区。
6. **第二批与流派删除**：#46–#55 只列不排期；流派页删除作为独立小工单，按第 5 节步骤执行。

## 3. 主流程问题清单

> 下表按屏分组，共 57 行，代表主流程 63 条原始记录（6 条同源重复已并入对应行）。位置列只写材料中给出的行号，不新增推断行号。置信度列中 `unverified` 一律标「待真机确认」。

### 3.1 首页框架（12 条）

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P0 | `MainActivity.kt:4082` | **#0 深色主题下迷你播放条与玻璃岛的图标/歌名仍是默认黑色，深色玻璃上几乎不可读** | 歌名 `Text`（4082-4093）未指定颜色；上一首/播放/下一首/展开四个 `Icon`（4127/4115/4140/4148）未给 tint；玻璃岛未选中项取 `LocalContentColor.current`（1537-1541/1551-1555），退出图标直接继承（1562）。`PulsarTheme` 只调 `MaterialTheme`（Theme.kt:101-105），外壳链路无 Surface/LocalContentColor 提供者；material3 1.4.0 默认 `LocalContentColor=Color.Black`（字节码确认），深色面板底色 `DarkSurface #1E293B`。 | 外壳统一提供 `LocalContentColor=onBackground`，或把玻璃内容包进 `GlassPanel`/Surface；不要逐个 Icon 写 tint。 | high（已复核） |
| P2 | `MainActivity.kt:1302` | **#1 Snackbar 未计底部导航栏 inset，弹出时压住迷你播放条** | `SnackbarHost` 固定 `bottom = 80.dp + if (playerExpanded) 320.dp else 0.dp`（1298-1304），没有 `navigationBarsPadding()`；同层 `MiniPlayerBar` 有该 padding（1291-1294），根布局 edge-to-edge（286-295）。手势导航约 24dp、三键约 48dp 时，80dp 底距落进 64dp 高的条内 16–40dp；320dp 也与展开面板实际高度无关。 | Snackbar 加 `navigationBarsPadding()`，底距改用实测 dock 高度；不要固定为 80/320。 | high（已复核） |
| P2 | `MainActivity.kt:4081` | **#2 迷你条 4 个 48dp 按钮把歌名挤到约 68dp，空态占位文案也会省略** | 行内固定占用 40dp 封面 + 12dp 间距 + 4×48dp 按钮（4113/4124/4138/4146），再加内外各 12dp 边距（4068、1291-1294）；360dp 屏文字列约 68dp，“未选择歌曲”约 70dp，必然 Ellipsis。 | 常驻按钮收进展开面板或减到 3 个，保证歌名列至少占屏宽一半。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1361` | **#3 多选操作条是唯一不用液态玻璃的悬浮条，圆角/内边距也与玻璃岛不一致** | 多选条用不透明 `Surface` + `RoundedCornerShape(16.dp)` + `surface.copy(alpha=0.96f)` + `tonalElevation=2.dp` + `shadowElevation=8.dp`（1356-1364），内边距 16/8/4dp（1360/1367）；玻璃岛/迷你条走 `liquidGlass` + `LiquidGlassPanelShape`（20dp，LiquidGlass.kt:40）。 | 改用 `liquidGlass` + 20dp 面板形状，或至少用 `Dimens.CornerLarge` 并登记例外原因。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:4068` | **#4 MiniPlayerBar 全套尺寸仍是硬编码 dp，与同文件 GlassChipBar 的 Dimens 用法分裂** | 迷你条固定为 12/8dp（4068）、12dp 间距（4079）、4×48dp（4113/4124/4138/4146）、队列 `heightIn(max=240.dp)`（4271）、移除按钮 40dp（4335）、进度线 3dp（4358）；GlassChipBar 已用 `Dimens.TouchTargetMedium/SpaceSmall/TouchTargetSmall`（1474-1475/1486/1532/1546/1560）。 | 12/8/48/240/40 等换成 Dimens；队列高度补 token。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:4233/4335`（#56、#69 同源） | **#5 队列面板两个触摸目标低于 48dp：标题行约 32dp、移除按钮 40dp** | 标题行 `Row(...).clickable{...}.padding(vertical=4.dp)`（4233-4238），内容 `titleSmall` + 24dp 图标，可点高约 32dp；移除 `IconButton(Modifier.size(40.dp))`（4330-4338），图标仅 16dp；同面板其它按钮均 48dp（4113/4124/4138/4146），`Dimens.TouchTargetSmall=48.dp`。 | 标题行 `heightIn(min=Dimens.TouchTargetSmall)`；移除按钮恢复 48dp 热区、图标保持 16dp。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1558` | **#6 退出登录紧挨设置、单击立即登出且无确认** | 玻璃岛尾部三个同级 `IconButton`：搜索 1530、设置 1544、退出 1558-1563；退出用 `Icons.Filled.ExitToApp`、48dp，与设置仅隔 8dp；`onSignOut=viewModel::signOut` 直接生效（1259）。 | 退出移入设置页或二次确认；图标语义改成更明确的“退出登录”。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:4243` | **#7 队列分区标题与歌曲名同用 titleSmall，展开面板没有层级** | 分区标题 `Text("播放队列（N 首）", titleSmall)`（4241-4245）；队列项歌名同样 `titleSmall`（4311-4318）。 | 分区标题改 `titleMedium` 或 `labelLarge`，拉开字重/颜色层级。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:4437` | **#8 AlbumCover 底部渐变固定 32dp，40dp 迷你封面上覆盖 80%** | 固定 `height(32.dp)` 渐变（4434-4444）；AlbumCover 同时用于 40dp 迷你封面（3935-3956）和全屏大封面。 | 渐变高度按封面比例（如 `fillMaxHeight(0.25f)` 并 `heightIn(max=32.dp)`）。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:3937` | **#9 迷你条空态占位是直角灰块，与列表封面占位不一致** | `MiniPlayerCover` 空态仅 `Box(fillMaxSize().background(surfaceVariant))`（3936-3938），无 clip；`AlbumCover` 占位有 8dp 圆角 + 阴影（4392-4406）。 | 空态复用 `AlbumCover(null, ...)` 或补 8dp 圆角。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:393` | **#10 ErrorState/EmptyState 硬编码 dp 且两态文本对齐不一致** | 两态均为 16dp padding、12dp 间距、48dp 图标（392-401、419-428）；ErrorState 文本 `TextAlign.Center`（406），EmptyState 没有。 | 改用 `Dimens.SpaceMedium/SpaceMidMedium/TouchTargetSmall`；两态统一居中。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1352` | **#11 音量浮窗与多选操作条占用同一个顶部槽位，多选时音量反馈被完全盖住** | 音量浮窗 `TopCenter + statusBarsPadding + top=GlassIslandTopSpacing+8.dp`（1349-1352）；多选条使用完全相同的顶部起点（1358-1360），且声明在后、绘制在上层。 | 音量浮窗改到底部 overlay 栈，或多选时下移；顶部放导航/选择，底部放瞬时反馈。 | unverified · 待真机确认 |

### 3.2 歌曲与共享交互（10 条）

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P2 | `MainActivity.kt:1949` | **#12 长按菜单自绘“玻璃”，绕开 LiquidGlass 唯一出处；浅色模式下白描边白底不可见** | `Surface` 240dp、16dp 圆角、`surface.copy(alpha=0.96f)`、`BorderStroke(0.5.dp, Color.White.copy(alpha=暗0.25/亮0.6))`（1949-1961）；浅色 `LightSurface=#FFFFFF`（Theme.kt:41）。阴影仍在，不是完全无边界，但描边确实消失，属观感级问题。 | 改走同窗 overlay + `GlassPanel`；若 Popup 采不到 backdrop，至少把描边改 `outlineVariant/onSurface` 并登记例外。 | high（已复核） |
| P2 | `MainActivity.kt:1950` | **#13 菜单宽高/内边距/圆角硬编码 dp，未取 Dimens token** | `width(240.dp)`、`RoundedCornerShape(16.dp)`（1950-1951）、行内 20/14dp（2139/2143）、外层 16dp（1940）；Dimens 已有 16/20/16 对应值。 | 16/20dp 与圆角换 Dimens；240dp 宽度与 14dp 图标间距保留并注释。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1621` | **#14 歌曲页空状态未撑满宽度，图标与文案贴列表左缘** | `EmptyState(...)` 未传 `Modifier.fillMaxWidth()`（1621）；`EmptyState` 内部 `Column(modifier.padding(16.dp), horizontalAlignment=CenterHorizontally)`（419-433）按内容收缩；专辑详情用法传了 `fillMaxWidth()`（2878）。 | 调用处补 `Modifier.fillMaxWidth()`，与专辑详情对齐。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1606` | **#15 歌曲页标题硬编码“你的音乐”，与顶层页签“歌曲”不一致** | `Text("你的音乐", titleLarge)`（1606）；`LibrarySection.kt:39` 定义 `LibrarySection.Tracks` 文案为“歌曲”；CLAUDE.md 约定 chip/占位页标题从 `TopLevelSections` 取。 | 标题从 `TopLevelSections/navLabel()` 取，或统一导航文案。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2101` | **#16 删除用默认/主色呈现，破坏性操作没有 error 配色** | 删除确认 `TextButton` 用默认色（2101）；长按菜单删除图标 `tint=colorScheme.primary`（1995），与“改名”同色；回收站不可逆操作使用 `colorScheme.error`（3698）。 | 菜单删除项与确认按钮改 `colorScheme.error`。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1845` | **#17 暂停态均衡器条用 onSurfaceVariant，压在 35% 黑遮罩封面上对比不足** | `barColor = if (isPlaying) LocalCoverAccent.current else onSurfaceVariant`（1845）；遮罩 `scrim.copy(alpha=0.35f)`（1869）；浅色 `onSurfaceVariant=#64748B`（Theme.kt:44），3dp 细条容易糊进封面。 | 暂停态改用与封面亮度无关的角色（白/黑/onPrimary），或提高遮罩不透明度。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2047` | **#18 改名弹窗三个输入框塞在 AlertDialog text 槽内且无滚动，键盘/大字体下可能被裁** | `text = { Column(fillMaxWidth, spacedBy(8.dp)) { 三个 OutlinedTextField + fileOpsMessage + 说明 } }`（2047 起），无 `verticalScroll`/`imePadding`。 | text 内容加 `verticalScroll` + `imePadding`，说明移出 text 槽。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1814` | **#19 多选模式下分隔线缩进未跟随行首 Checkbox，线与文字错位** | `HorizontalDivider` 固定 `start=48+12=60dp`（1814）；多选时行首先插 Checkbox + 4dp Spacer（1732-1738），标题起点约 112dp。 | 分隔线起点按当前 leading 宽度计算（多选加 Checkbox 宽度）。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1603` 等 | **#20 底部内容留白 96.dp 在多处重复硬编码** | 问题 JSON 记录 10 处：490/551/571/1603/2205/2651/2704/2792/2944/3281；重设计方案 grep 到 12 处（另含 3092/3744）。同文件顶部占位已抽 `GlassIslandTopSpacing`（751），底部仍是字面量。 | 抽 `BottomContentInset=96.dp` 或与 `GlassIslandTopSpacing` 并列的常量，统一替换；验收 grep `96.dp` 为 0。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1608` | **#21 离线过滤提示用 labelSmall(11sp)，比行内歌手副标题(12sp)还弱** | `OfflineNoticeText` 用 `labelSmall`（438-443，11sp/16sp）；同列表歌手行 `bodySmall`（12sp/16sp，1783）。 | 改 `bodySmall` 或 `labelMedium`，不低于行内副标题。 | unverified · 待真机确认 |

### 3.3 发现与专辑（10 条）

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P1 | `MainActivity.kt:2215` | **#22 离线时发现页三节历史未过滤，仍显示未下载内容** | 2215 `tracks=state.recentlyPlayed`、2227 `tracks=state.mostPlayed`、2239 `albums=state.recentlyAdded` 直接取原始 state；同函数 2160-2161 的 `tracks/discoverAlbums` 是调用方过滤后的列表；2259 又写“离线 · 只显示已下载”。`goOffline()` 不清这三个字段，断网后旧数据原样显示；2344 `hasAnySection` 也用原始字段，会压制整页空态。 | 三节历史按 `cachedTrackIds` 过滤（复用 `offlineVisibleLibrary` 规则）后再传给 strip；`hasAnySection` 改用派生值。 | high（已复核） |
| P2 | `MainActivity.kt:2193` | **#23 下拉刷新转圈被状态栏和顶部玻璃岛遮住** | `PullToRefreshBox` 从屏幕顶端铺起（2193-2200），父层只有 `navigationBarsPadding`（1044-1049）；LazyColumn 才加 `statusBars` + `top=GlassIslandTopSpacing`（2202-2205）；默认 indicator 最多下移 80dp，小于玻璃岛底部（statusBar+8+56，1260-1263）。 | 刷新层加状态栏 + 玻璃岛高度顶部内边距，或自定义 indicator。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2461` | **#24 横滑骨架的第三张卡被 Row 压窄** | 注释称“三张与真卡同宽”（2452），实际普通 `Row` + `repeat(3){ Column(width(DiscoverCardWidth)) }`（2456-2461）；`DiscoverCardWidth=140.dp`（2485），3×140+2×12=444dp 超过 360dp 屏内容宽 328dp，第三张被压到约 24dp。 | 改用不可滚动 `LazyRow`，第三张由视口裁切而非压缩。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2270` | **#25 空曲库时每日推荐空态文案错误且与整页空态重复** | `state.tracks == null` 走骨架（2261），能到 2267-2270 时曲库已非空且为空列表，文案却是“登录后这里会推荐当天的歌曲”；下方 2349-2355 还会再显示整页空态；离线时两处文字完全相同（2270 与 2352）。 | 登录态改“曲库为空”类文案；每日推荐空态与整页空态只保留一处。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2353` | **#26 整页空态提示的“在设置里刷新曲库”并不存在** | 2353 文案“去搜索页搜歌并下载，或在设置里刷新曲库”；全仓库只有这一处“刷新曲库”，设置页无对应入口。 | 改成真实入口（重新登录会重拉曲库），或补设置页刷新按钮。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2610` | **#27 专辑网格卡副标题与标题同色且不限行** | `Text(album.artist + trackCount, bodyMedium)` 无 color、无 maxLines（2609-2612）；同页 `DiscoverAlbumCard` 是 `bodySmall + onSurfaceVariant + maxLines=1 + Ellipsis`（2563-2569）。 | 副标题改 `onSurfaceVariant` + `maxLines=1`；标题固定/预留两行高度。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2827` | **#28 专辑详情离线时无过滤提示，摘要按已下载曲目计数** | `hideDownloadButton` 是唯一离线信号（2754）；头部 2796-2868 无 `OfflineNoticeText`；`trackList=albumTracks.orEmpty()`（2770），`albumSummary(album, trackList)`（2827）按离线过滤后的子集算“N 首 · M 分钟”。 | 详情补离线提示；摘要改用 `album.trackCount` 或显示“已下载 N/总数”。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2724`（#57 同源） | **#29 详情骨架硬编码 16/12/48dp，且行内边距 6dp 与真实 TrackRow 8dp 不一致** | `AlbumDetailSkeleton` 固定为 `spacedBy(16.dp)`（2724）、`spacedBy(8.dp)`（2726）、`top=12.dp`（2731）、`size(48.dp)`（2737）、`width(12.dp)`（2738）、`spacedBy(4.dp)`（2739），行 `padding(vertical=6.dp)`（2734）；真实 `TrackRow` 用 `Dimens.SpaceSmall=8dp`（1728）。6 行累计差约 24dp。 | 全部换 Dimens（16/8/12/4），行内边距改 `Dimens.SpaceSmall`。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:2303` | **#30 “换一批”旋转动画结束时瞬间回正** | 注释写“结束后停在当前角度”（2175），实际 `if (state.isLoading) Modifier.rotate(refreshRotation) else Modifier`（2303），`isLoading=false` 即摘掉 rotate，图标从当前角度跳回 0°。 | 用 `Animatable` 结束时 `animateTo` 最近整圈角度，或改注释。 | unverified · 待真机确认 |
| P2（原 #31 P3 / #67 P2，取高） | `MainActivity.kt:2872`（#67 同源） | **#31 专辑详情骨架用封面方块，真实行却是序号，且骨架缺尾部 48dp 占位** | 加载分支 `TrackListSkeleton(count=6, fillMaxWidth)`（2872）；骨架行首 `SkeletonBox(48dp)`（668）；真实行 `TrackRow(..., leadingNumber=index+1)` 且传 `onAddToQueue`（2880-2890），TrackRow 会显示 48dp“更多”按钮（1791-1809）。 | 为专辑详情提供序号模式骨架并传 `trailingSpace=true`，或扩展 `TrackListSkeleton`。 | unverified · 待真机确认 |

### 3.4 艺术家与播放列表（12 条）

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P2 | `MainActivity.kt:3272`（#66 同源） | **#32 播放列表详情加载态把头部重复画了一遍** | `playlistTracks == null && isLoading -> AlbumDetailSkeleton(fillMaxSize)`（3272）；`AlbumDetailSkeleton` 首行是 120dp 灰封面 + 文字条（2724-2725），而真实头部 120dp 封面/名称/下载按钮已在 3246-3269 显示。加载完成骨架消失，页面跳动。 | 加载态只渲染不含头部的 `TrackListSkeleton`；删重复 `AlbumDetailSkeleton` 调用。 | high（已复核） |
| P2 | `MainActivity.kt:479` | **#33 艺术家/流派骨架没撑满，被居中后加载完瞬移回顶部** | `Box(weight(1f).fillMaxWidth(), contentAlignment=Center)` 内放 `TrackListSkeleton(count=8)` 未传 modifier（479）；同类 `PlaylistsContent` 已改成 `TrackListSkeleton(count=8, Modifier.fillMaxSize())`（2935，注释说明避免跳动）。 | 传 `Modifier.fillMaxSize()`，从顶部开始。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:555` | **#34 组详情骨架只有封面方块，真实卡片多了文字区，加载完行高跳变** | 骨架 `items(6){ SkeletonBox(fillMaxWidth().aspectRatio(1f), ...) }`（555-561）；真实 `AlbumGridCard` 还有 20dp padding + 两行文字 + 1dp 描边（2599-2613）。 | 骨架补两根文字条和外描边，与 `AlbumGridCard` 同高。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3243` | **#35 播放列表详情头部整片硬编码 dp，未走 Dimens** | `padding(horizontal=16.dp).padding(top=8.dp)`（3243）、`spacedBy(12.dp)`（3244）、`spacedBy(16.dp)`（3246）、`size(120.dp)`（3247）、`spacedBy(4.dp)`（3282）；相邻 `AlbumDetailContent` 全用 Dimens（2788-2806）。 | 换 `Dimens.SpaceMedium/SpaceSmall/SpaceMidMedium/SpaceTiny`；120dp 封面补 token 或复用。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3251` | **#36 头部曲目数和列表实际行数不同源，离线过滤后会对不上** | 头部 `playlist.trackCount?.let { "$it 首" }`（3251）；列表 `playlistTracks` 来自 `visible.playlistTracks`（1199），离线会按已下载 id 过滤（OfflineVisibleLibrary.kt:82-83）；专辑详情 `albumSummary(album, trackList)` 优先用已加载列表（2899-2907）。 | 曲目已加载用 `playlistTracks.size`，未加载才退回 `trackCount`；离线显示“已下载 N/共 M”。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3246` | **#37 播放列表详情缺少“播放全部 / 随机播放”** | 头部 Row 只有封面、名称、曲目数和下载按钮（3246-3269）；专辑详情同位置有播放全部 + 随机播放（2832-2852）。 | 复用专辑详情那对控件，操作当前实际曲目列表。 | unverified · 待真机确认 |
| P2 | `DownloadActionButton.kt:94`（#73 同源） | **#38 字节校验完成前，已下载内容先显示成可点的“下载”** | `allComplete` 初始 `false`（94），随后 `LaunchedEffect` 逐首 `hasCompleteCopy`（96-99）；`resolveDownloadState` 在 false 时返回 `NotDownloaded`（253-258），按钮 `enabled = target != null`。打开已下载专辑会先闪“下载”，手快会重复入队。 | `allComplete` 改 `Boolean?`，null=“检查中…”禁用态；确定后再显示下载/已下载。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:3252` | **#39 副标题没有指定次要色，和标题同一强调级** | `Text(trackCount, bodyMedium)` 未传 color（3252），默认 `onSurface`；标题 `titleLarge`（3249）同色；组详情副信息显式 `onSurfaceVariant`（537-539）。 | 副标题改 `onSurfaceVariant`，字号降到 `bodySmall/bodyMedium`。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:3247` | **#40 详情大封面圆角比专辑详情小一号** | `AlbumCover(..., Modifier.size(120.dp))` 未传 `cornerRadius`，走默认 8dp（4390）；专辑详情显式 `Dimens.CornerLarge`（2804）。 | 传 `cornerRadius = Dimens.CornerLarge`。 | unverified · 待真机确认 |
| P3 | `DownloadActionButton.kt:180` | **#41 失败态用 TextButton，强调级反而低于其它三态** | `PartiallyFailed -> TextButton { "N 首失败 · 重试" }`（180）；下载中/已下载/未下载均为 `FilledTonalButton`（154/172/184）。 | 统一 `FilledTonalButton`（可用 errorContainer），加刷新图标。 | unverified · 待真机确认 |
| P3 | `DownloadActionButton.kt:215` | **#42 确认删除后只有按钮变灰，没有任何进度反馈** | `showDeleteConfirm` 的 AlertDialog 确认按钮 `enabled=!deleting`（215 起）；`deleting=true` 后逐首 `downloads.remove` 再写 DataStore（129-150），对话框不关、无进度。 | 确认后关闭对话框并用 Snackbar 报告“已删除 N 首”，或按钮显示进度。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:3273` | **#43 离线打开未下载的播放列表，页面给了一个必然失败的重试按钮** | `playlistTracks == null && errorMessage != null -> ErrorState(errorMessage, onRetry)`（3273）；`onRetry` 调 `loadPlaylistTracks`（1205），离线且无清单时又写同一句“需要联网才能查看”（JellyfinViewModel.kt:646-656）。 | 离线且未下载时不渲染重试按钮，改说明型空态。 | unverified · 待真机确认 |

### 3.5 横切问题（13 条）

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P3 | `MainActivity.kt:4168` | **#58 小展开面板与队列行的间距硬编码** | 面板 `padding(horizontal=16.dp, vertical=8.dp)`（4168）、`spacedBy(4.dp)`（4171）、`top=4.dp`（4219）、`top=8.dp`（4236）、`vertical=4.dp`（4238/4297）；均为 Dimens 已有值。 | 换 `Dimens.SpaceMedium/SpaceSmall/SpaceTiny`。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1912` | **#59 长按菜单的阴影安全区两处 16.dp 需人工同步** | 定位 `shadowPaddingPx=16.dp`（1912），内容外层 `padding(16.dp)`（1940），圆角 `16.dp`（1951）；两个 16dp 必须永远相等，否则菜单偏离长按点或裁阴影。 | 抽 `MenuEdgePadding=Dimens.SpaceMedium`，1912/1940 共用；圆角用 `Dimens.CornerLarge`。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1360` | **#60 多选操作条的悬浮偏移、内边距和圆角硬编码** | `top=GlassIslandTopSpacing+8.dp, start/end=16.dp`（1360）、`RoundedCornerShape(16.dp)`（1361）、内部 `start=16/end=4.dp`（1367）；音量浮窗同位置也写 `+8.dp`（1352）。 | 8/16/4dp 与圆角改 Dimens；顶偏移抽共享常量。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:704` | **#61 登录页容器内边距与字段间距硬编码** | `padding(24.dp)`（704）、`spacedBy(16.dp)`（705），对应 `Dimens.SpaceLarge/SpaceMedium`。 | 改用 Dimens。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1263` | **#62 玻璃岛、迷你条、Snackbar 的外边距各自硬编码 12/8dp** | GlassChipBar `padding(horizontal=12.dp, vertical=8.dp)`（1263）；MiniPlayerBar 同值（1294）；Snackbar `horizontal=12.dp`（1303）。 | 抽 `FloatingBarHorizontalMargin`/`FloatingBarVerticalMargin` 或直接引 Dimens。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:4360` | **#63 迷你条进度线的 20dp 圆角与玻璃面板圆角重复定义** | `RoundedCornerShape(bottomStart=20.dp, bottomEnd=20.dp)`（4360）；面板实际来自 `LiquidGlassPanelShape=RoundedCornerShape(20.dp)`（LiquidGlass.kt:40），`Dimens.CornerExtraLarge=20.dp`。 | 底部两角改 `Dimens.CornerExtraLarge` 或复用面板圆角常量。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:1960` | **#64 长按菜单的玻璃高光用 Color.White 字面量** | `Color.White.copy(alpha=暗0.25/亮0.6)`（1960）；全 App 其它玻璃高光由 LiquidGlass 的 drawBackdrop 统一绘制。 | 高光色/alpha 收进 LiquidGlass.kt 共享常量。 | unverified · 待真机确认 |
| P2 | `DownloadActionButton.kt:180` | **#65 部分失败重试绕过移动数据确认** | 正常下载走 `requestDownload()`（113-115 先判断 `isOnCellular` 并弹确认）；`PartiallyFailed -> TextButton(onClick=::enqueue)`（180）直接入队，跳过确认。 | 重试也走 `requestDownload()`，或抽统一“蜂窝确认后 enqueue”入口。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3249` | **#68 播放列表详情标题无 maxLines，长名称撑高不可滚动头部** | `Text(playlist.name, titleLarge)` 无 maxLines/overflow（3249）；外层 Column 无 `weight(1f)`（3248），头部不在 LazyColumn 内；长名称会挤小 `weight(1f)` 曲目列表。 | 文本列加 `weight(1f)`；标题 `maxLines=2 + Ellipsis`。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:2177` | **#70 发现页“换一批”无限旋转动画常驻运行** | `rememberInfiniteTransition` + `animateFloat(0→360, tween(800))` 在 DiscoverContent 组合期间一直运行（2177-2182）；只有 `state.isLoading` 时才用于 rotate（2303）。 | 只在 isLoading 时创建/启动 transition，空闲停掉。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:1846` | **#71 播放指示器暂停后仍无限动画，且逐帧修改高度** | `rememberInfiniteTransition("playingBars")` 未按 `isPlaying` 启停（1846）；`PlayingBar(if (isPlaying) barHeight(...) else 4.dp, ...)`（1873-1875），内部 `Modifier.height(height)`（1883-1888）。暂停仍动画；播放时逐帧 remeasure。 | `isPlaying=false` 不组合 transition；播放时用 `graphicsLayer scaleY` 或 draw 阶段绘制。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:2678` | **#72 专辑网格骨架文字条高度与真实卡片不一致** | 骨架标题条 16dp、副标题条 14dp（2674-2688）；真实标题 `titleMedium` lineHeight 24sp、副标题 `bodyMedium` 20sp（2603-2612）。每卡矮约 14dp，两行标题差约 38dp。 | 骨架条按 24/20dp 绘制，标题预留两行。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:4112` | **#74 迷你播放条控制按钮无媒体时仍显示可用** | 上一首/播放/下一首 `enabled = controller != null`（4112/4123/4137）；`hasMedia` 在 3980 已算出，却只用于 4192 进度条。队列为空时按钮可点、有触觉但无动作。 | 上一首/播放/下一首改用 `hasMedia` 或 `controller?.mediaItemCount > 0`。 | unverified · 待真机确认 |

> 已并入本节的重复项：#56/#69 → #5；#57 → #29；#66 → #32；#67 → #31；#73 → #38。

## 4. 重设计提案：首页框架 / 列表与详情

### 4.1 边界与核心判断

- 只改外壳、列表与详情；**不改**液态玻璃材质、M3 色板、封面取色。
- **不做底部导航**：底部已被播放 dock 占用，再加 56dp 导航会把内容区压到 500dp 以下；顶部玻璃岛保留，但收敛为「搜索 + 可横滑页签 + 一个溢出菜单」。
- 这是“外壳收敛”，不是换一套视觉。

六条根因：

| 根因 | 典型表现 | 主要涉及 |
| --- | --- | --- |
| R1 玻璃只有 draw，没有前景色 | 深色下歌名/图标/时间/音量数字全黑；浅色下恰好能看所以一直没暴露 | #0 及全部深色玻璃面 |
| R2 浮层没有统一几何与锚点 | 12/8/80/320/96 硬编码值；Snackbar 压 dock；音量被多选条盖住 | #1/#11/#44/#62/#63 |
| R3 列表没有统一行语法 | 分隔线缩进错、骨架 6/8dp、空态贴左、搜索行另写一份 | #14/#15/#19/#29/#31/#33/#34 |
| R4 详情没有统一页面骨架 | 两套头部、重复骨架、计数不同源、无 maxLines | #32/#36/#37/#40/#68 |
| R5 离线派生只覆盖顶层列表 | 发现页三节历史未过滤、详情摘要按已下载计数 | #22/#28/#36/#43 |
| R6 下载/动效缺“未知/停止”态 | 已下载先闪“下载”、蜂窝重试绕过确认、动画常驻 | #38/#42/#70/#71/#74 |

### 4.2 首页框架

**1）统一玻璃前景色。** 在 `ui/components/LiquidGlass.kt` 增加薄包装 `GlassPanel`：内部 `CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface)`，再套 `liquidGlass(...)`。顶部岛、迷你条、音量胶囊、多选条全部改走它。这一步同时消灭 P0 的黑图标、黑歌名、黑时间、黑音量数字。不要给每个 Icon/Text 单独写 tint——那会把同一个问题修 20 遍。

**2）玻璃岛收敛为三段式。** `[搜索 48] [可横滑页签 weight(1)] [⋯ 48]`，高 56dp、圆角 20dp、左右外边距 12dp。设置、已下载、QQ 下载、退出登录全部收进 `⋯` 溢出菜单；退出登录加二次确认。页签顺序与文案仍由 `TopLevelSections` 决定。

**3）迷你条做减法。** 折叠态只保留：封面 40 + 歌名/歌手（weight）+ 播放/暂停 48 + 展开 48。上一首/下一首从折叠条移除（横滑与全屏页仍在）；播放/暂停 `enabled = controller != null && hasMedia`。整条点击仍打开全屏页，横向滑动仍切歌。

折叠态线框（360dp 屏，左右各 12dp 外边距 → 条宽 336dp）：

```text
┌──────────────────────────────────────────────┐
│ status bar                                   │
│ ┌──────────────────────────────────────────┐ │ 8dp
│ │ [封面 40] 歌名 164dp        [播放 48] [展开 48] │ │ 玻璃条 64dp / 圆角 20
│ │           歌手 12sp                       │ │
│ │▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁│ │ 进度线 3dp
│ └──────────────────────────────────────────┘ │ 8dp
└──────────────────────────────────────────────┘ nav inset
```

横向核算：336 − 2×12 内边距 = 312；封面 40 + 间距 12 + 文字 164 + 播放 48 + 展开 48 = 312。歌名从约 68dp 提到 **164dp**，约 10–12 个汉字。

**4）浮层统一锚点。** Snackbar 与音量浮窗放进同一个底部 overlay 栈：`navigationBarsPadding()` + `bottom = 8 + measuredDockHeight + 8`。`MiniPlayerBar` 外层用 `onGloballyPositioned` 上报高度（展开面板是它的一部分，高度自然包含面板），首帧用 `PlayerDockHeight` 兜底。多选条固定在玻璃岛下方（top = 72+8），改用 `GlassPanel`，高 56、左右 12、圆角 20、动作 48dp；音量浮窗改到底部。规则一句话：**顶部槽位放导航/选择，底部槽位放瞬时反馈**。

**5）外壳 token 统一管理。** 在 `ui/theme/Dimens.kt` 增加：`FloatingHorizontalMargin=12dp`、`FloatingVerticalMargin=8dp`、`GlassIslandHeight=56dp`、`GlassIslandTopSpacing=72dp`（=56+8×2）、`PlayerDockHeight=64dp`（=48+8×2）、`PlayerDockCover=40dp`、`BottomContentInset=96dp`、`QueueRowMinHeight=48dp`、`MenuEdgePadding=16dp`。组件内部尺寸（如 40dp 封面）可留在组件，但所有外壳间距、触摸目标、圆角必须来自 token。

**6）运动与占位。** 播放指示器只在 `isPlaying` 时创建无限动画，且用 `drawBehind` 画条，不逐帧改高度；暂停时静态。发现页“换一批”用 `Animatable`：加载时循环，停止时补到最近的 360° 整倍数再停。迷你空态占位改用 `AlbumCover(null, …)`，自动获得 8dp 圆角。

### 4.3 列表与详情

**1）一套行语法 `MediaRowLayout`。** 新增共享布局：`leading` 槽 48dp（封面 / 序号 / 勾选框+封面）、间距 12dp、文字列 `weight(1f)`、`trailing` 槽 48dp（更多按钮，可空）、上下 padding 8dp、最小行高 64dp。标题 `bodyLarge` 1 行、副标题 `bodySmall` 次要色 1 行；当前曲目标题 primary。分隔线起点 = 实际 leading 宽度 + 12dp（无多选 60dp，多选 112dp，序号 60dp）。`TrackRow`、`SearchResultRow`、播放列表行、艺术家/流派行全部走它。

**2）一套详情骨架 `MediaDetailScaffold`。** 整页一个 LazyColumn：header item（真实对象，永远不用骨架）→ 操作行 → 下载槽 → 离线提示 → 曲目状态（骨架/错误/空/真实行）。专辑与播放列表都走它；组详情走它的“网格变体”。这样同时解决：重复头部骨架、标题无 maxLines、`weight(1f)` 挤压、计数不同源、圆角不一致、缺少播放全部/随机播放。

**3）详情头部统一规格。** 封面 `fillMaxWidth(0.62f)` + `aspectRatio(1f)` + 16dp 圆角；标题 `headlineSmall` 粗体、`maxLines=2`、居中；副标题 `bodyLarge` 次要色 1 行；摘要 `bodySmall` 次要色；离线时摘要改为“已下载 N 首 · 共 M 首”，并在下面加离线提示。播放列表不再用左侧 120dp 小封面 + 左对齐文字，改为与专辑同一语言；区别只有专辑带封面色晕。

详情页线框：

```text
┌──────────────────────────────────────────────┐
│ status bar                                   │
│ [可选：专辑封面色晕，占上半屏 72% 高]         │
│ LazyColumn 全页滚动：top 16 / 左右 16 / bottom 96
│   ┌────────────────────────────────────┐     │
│   │        封面 62% 宽 · 1:1 · 16r      │     │
│   └────────────────────────────────────┘     │
│   24dp                                       │
│   [标题 headlineSmall 粗体 · 居中 · max 2 行] │
│   4dp [歌手/作者 bodyLarge 次要色 · 1 行]     │
│   4dp [摘要 bodySmall 次要色]                 │
│   4dp [离线提示 bodySmall]                    │
│   16dp                                       │
│   [播放全部 40]  12  [随机播放 40]            │
│   12dp                                       │
│   [下载 / 检查中… / 已下载 / N 首失败·重试]    │
│   24dp                                       │
│   [行 64：序号 48 | 12 | 歌名/歌手 | 更多 48]  │
│   …                                          │
└──────────────────────────────────────────────┘
```

**4）操作集对齐。** 专辑和播放列表都有“播放全部 / 随机播放”，按钮 40dp 高、间距 12dp；下载按钮在操作行下方 12dp；离线时隐藏下载。播放列表的播放/随机使用当前实际曲目列表（离线已过滤）。

**5）骨架与真实同构。** `TrackListSkeleton` 复刻 `MediaRowLayout` 的 48/12/48 槽位与 8dp 内边距；专辑详情的骨架 leading 用“序号槽”而不是假封面；组详情网格骨架必须带 2 行标题 + 1 行副标题的文字区，并 `fillMaxSize()` 从顶部开始，不能居中后瞬移。

**6）专辑网格稳定卡高。** `AlbumGridCard` 标题用 `minLines=2, maxLines=2` 预留两行；副标题 `onSurfaceVariant`、`maxLines=1`。短名/长名卡片同高，2 列网格底边不再参差，也顺手让骨架与真实卡同高。

**7）离线派生补全。** `VisibleLibrary` 增加 `recentlyPlayed/mostPlayed/recentlyAdded`，由 `offlineVisibleLibrary` 用同一套规则过滤（曲目按 id，专辑按“有一首已下载”）；发现页与 `hasAnySection` 全部改用派生值。详情摘要按实际可见曲目计数并显示离线提示。`null` 仍保持 `null`，避免把“没拉到”误判成“空”。

**8）下载按钮补“未知态”和单一入队漏斗。** 字节校验完成前显示禁用的“检查中…”（不再闪可点的“下载”）；所有重试/下载入口统一走 `requestDownload()`，蜂窝确认只写一次；失败态改成带 `Refresh` 图标的 `FilledTonalButton`（errorContainer）；删除确认内显示进度，完成后通过回调弹“已删除 N 首”。

**9）菜单与对话框。** 长按菜单从 `Popup` 提升到主窗口 overlay，使用同一个 `liquidGlass` / `GlassPanel`（`Popup` 是独立窗口，采样不到主窗口 Backdrop，这是它当初只能手写玻璃的技术原因）。菜单项 48dp，删除项 error 色，宽 240dp、安全区一个常量。改名弹窗内容区可滚动并处理 IME，按钮区固定在滚动区外。

**10）层级与对比。** 离线提示 `bodySmall`；暂停指示器遮罩从 0.35 提到 0.45–0.5，暂停条改白色；专辑网格副标题次要色；播放列表副标题次要色；歌曲页标题直接取 `TopLevelSections` 的“歌曲”。

### 4.4 组件级改动清单（节选）

| 区域 | 文件:行号 | 改动 | 覆盖问题 |
| --- | --- | --- | --- |
| 玻璃 | `ui/components/LiquidGlass.kt:53-69` | 新增 `GlassPanel`，提供 `LocalContentColor=onSurface` 后再 `liquidGlass` | #0 |
| 玻璃 | `ui/components/VolumeFeedbackOverlay.kt:78-103` | 音量胶囊改 `GlassPanel`；尺寸 168×44 保留 | #0/#11 |
| token | `ui/theme/Dimens.kt:17-40` | 增加外壳 token；`BottomContentInset`、`GlassIslandTopSpacing` 用表达式推导 | #1/#20/#62/#63 |
| 外壳 | `MainActivity.kt:1449-1565` | 岛改「搜索 + 页签 + ⋯」；设置/退出收进溢出菜单；退出确认 | #6 |
| 外壳 | `MainActivity.kt:1298-1304` | Snackbar 移入底部 overlay 栈：`navigationBarsPadding + 实测 dock + 8dp` | #1 |
| 外壳 | `MainActivity.kt:1345-1389` | 音量浮窗移到底部；多选条改 `GlassPanel`、top=72+8、高 56 | #3/#11/#60 |
| 外壳 | `MainActivity.kt:3961-4379` | 迷你条走 token；删上一首/下一首；播放键用 `hasMedia`；队列目标 48dp | #2/#4/#5/#74 |
| 空态 | `MainActivity.kt:386-445` | Error/Empty 走 Dimens 并统一居中；离线提示改 `bodySmall` | #10/#21 |
| 离线 | `OfflineVisibleLibrary.kt:15-92` | `VisibleLibrary` 增加三节历史字段与过滤；null 保持 null | #22/#28/#36 |
| 发现 | `MainActivity.kt:2212-2356` | 三节历史改用派生值；`hasAnySection` 同步；空态文案去重、去掉不存在的设置入口 | #22/#25/#26 |
| 列表 | 新增 `ui/components/MediaList.kt` | `MediaRowLayout` + `TrackRowSkeleton` + `AlbumCardSkeleton` | #19/#29/#31/#33/#34/#72 |
| 详情 | `MainActivity.kt:2750-2896`、`3225-3301` | 专辑/播放列表改 `MediaDetailScaffold`；播放列表补 maxLines、播放全部/随机、实际计数、离线提示 | #32/#36/#37/#40/#68 |
| 下载 | `DownloadActionButton.kt:94-234` | `allComplete: Boolean?`；统一 `requestDownload`；失败态 `FilledTonalButton`；删除进度/回调 | #38/#41/#42/#65 |
| 菜单 | `MainActivity.kt:1893-2023`、`2112-2146` | 菜单改根层 `GlassPanel`；240dp/48dp/16dp 安全区；删除 error 色 | #12/#13/#59/#64 |
| 动效 | `MainActivity.kt:2176-2182`、`1846-1889` | 换一批按 isLoading 启停并补整圈；播放指示器按 isPlaying 启停并 draw 化 | #70/#71 |
| 封面 | `MainActivity.kt:4434-4445` | 渐变改为比例高度 + 32dp 上限 | #8 |

### 4.5 实施顺序与验收

1. **A1–A4**：Dimens 外壳 token + `GlassPanel` + 音量胶囊内容色。验证：深色下岛/条/面板/音量全部可读；`assembleDebug`。
2. **A5–A9、A24**：浮层锚点、Snackbar inset、底部留白 token 清扫。验证：手势/三键导航下 Snackbar 不压 dock；grep `96.dp` 为 0。
3. **A11–A15**：迷你条减法、空态占位、队列目标/层级、进度线。验证：360dp 下歌名 ≥160dp；无媒体时播放键禁用；队列点击不点空。
4. **A7、A18–A20**：岛溢出菜单、退出确认、菜单 token、登录页 token。验证：设置/退出路径可走通；误触退出不再发生。
5. **A10、A16–A17**：多选玻璃条、Error/Empty 统一、离线提示字号。验证：多选 + 音量同时出现时两者都可见。
6. **A21–A23**：动效启停、封面渐变、指示器 draw 化。验证：静止时无持续动画帧；低端机滚动不掉帧。
7. **B1–B5 离线**：先写 `OfflineVisibleLibraryTest` 失败用例，再实现派生过滤与空态。验证：离线点历史歌/专辑不再进错页。
8. **B10–B11、B15、B23–B24**：`MediaRowLayout` + 行骨架 + divider + 搜索/播放列表/艺术家行迁移。验证：列表行高一致；多选分隔线从文字左缘起。
9. **B17–B22 详情**：先播放列表（重复头部、maxLines、计数、播放全部/随机、离线死路），再专辑，最后组详情。验证：播放列表不再两个头、长名不挤压、离线计数正确。
10. **B25–B27 下载**：未知态、蜂窝漏斗、失败态、删除进度。验证：`DownloadActionButtonTest`；Wi-Fi 下载→切蜂窝→失败重试应弹确认。
11. **B13–B14、B12、B28**：菜单同窗玻璃、改名弹窗滚动、暂停指示器、离线提示字号。验证：浅色菜单有边界；大字体/键盘下三行输入框可见；暂停态可辨。
12. **B6–B8、B29**：下拉刷新 indicator、横滑骨架、换一批动画、封面渐变。验证：下拉转圈在状态栏下方可见；骨架第三张卡被裁切而非压窄。
13. **全量真机验收 + `:app:testDebugUnitTest` + `:app:assembleDebug`**。

## 5. 流派页删除的影响与步骤

### 5.1 影响面

| 位置 | 牵连内容 | 处理 |
| --- | --- | --- |
| `LibrarySection.kt:42` | `TopLevelSections` 的流派入口 | 删除入口；顶层从 7 项变 6 项，chip 顺序与左右切换跟随变化 |
| `LibrarySectionTest.kt:19/58` | 顶层顺序/数量断言 | 同步修改；以 `LibrarySection` 类型而非硬编码索引断言 |
| `MainActivity.kt:452-590` | 艺术家/流派共用列表与详情，`byGenre/isGenre` 分支 | 删除流派分支，保留艺术家路径 |
| `MainActivity.kt:806-808`、`972-974`、`1121-1129`、`1215` | 流派相关分支/调用点 | 逐处删除，确认艺术家入口不受影响 |
| `LibraryGroups.kt:24` | `groupAlbumsByGenre` | 删除 |
| `LibraryGroups.kt:28` | `libraryGroups` | 简化为只按艺术家分组，迁移调用方 |
| `LibraryGroupsTest.kt` | 流派分组用例 | 删除流派用例，补艺术家-only 回归 |
| `Album.genres` 字段 | 数据模型/接口映射 | **保留**，不删字段、不改 DTO |

### 5.2 删除步骤

1. **移出入口**：从 `LibrarySection.kt` 的 `TopLevelSections` 删除流派项；同步 chip 文案与顺序测试。
2. **删流派分支**：移除 `MainActivity.kt:452-590` 的 `byGenre/isGenre` 路径，以及 806-808、972-974、1121-1129、1215 的流派分支/调用点；确保艺术家列表、艺术家详情、返回栈仍正常。
3. **简化分组**：`LibraryGroups.kt` 删除 `groupAlbumsByGenre`，`libraryGroups` 只按艺术家分组；调用方签名同步收敛。
4. **改测试**：`LibrarySectionTest.kt:19/58` 与 `LibraryGroupsTest.kt` 更新；补一条“艺术家分组大小写不敏感、空白/UNKNOWN_ARTIST 不建组”的既有边界回归。
5. **清理残留**：grep `genre` / `Genre` / `流派`，确认无孤儿入口、文案、空态分支。
6. **验证**：`:app:testDebugUnitTest`；真机走顶层左右切换、艺术家列表、艺术家详情；确认 `Album.genres` 仅作为数据字段保留，UI 不再引用。
7. **数据**：不涉及用户数据迁移，不删除任何本地数据；`Album.genres` 保留可避免接口层改动。

### 5.3 风险

- `navIndex()` / chip 顺序若存在硬编码索引，删项后会错位；应改为按 sealed 类型/列表位置推导，并让测试兜底。
- 艺术家与流派共用列表/详情时，删除分支容易误伤艺术家路径；建议单独提交、单独真机验证。
- 搜索、空态、详情页若出现“流派”文案，需一并清理；仅删入口会留下死文案。
- `libraryGroups` 签名变化会影响调用方与测试；先改实现再改调用方容易编译失败，建议一次改完。

## 6. 第二批（设置 / 搜索 / QQ 导入）问题清单

> 只列不排期。#44 是 P1，建议提前到主流程第 2 步一起修；#45 是 48dp 无障碍问题，可随第 5 步顺手修。其余按工单排后。

| 严重度 | 位置(文件:行号) | 问题 | 证据 | 修复方向 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| P1 | `MainActivity.kt:3328` | **#44 设置页无底部留白，末张“下载”卡片被常驻迷你播放条压住** | SettingsContent 根容器 `Column(fillMaxSize().statusBarsPadding().padding(horizontal=Dimens.SpaceMedium).verticalScroll(...))`，最后一个子项是 `SurfaceCard { DownloadsSection(...) }`，整页无 bottom padding；同批 search/已下载/QQ 导入都用 `contentPadding bottom=96.dp`。MiniPlayerBar 常驻且 `navigationBarsPadding`（1291-1294）。 | 给设置页滚动容器补约 96dp 底部占位，让最后一张卡滚到播放条上方。 | high（已复核） |
| P2 | `MainActivity.kt:3543` | **#45 配色预设色块 36dp 即可点，触摸目标小于 48dp** | `Box(Modifier.size(Dimens.ThemeSwatch).clip(CircleShape).background(...).clickable(...))`；`Dimens.ThemeSwatch=36.dp`，间距仅 8dp，无外层 48dp 触摸容器。 | 每个色块包至少 48dp 可点区域，或放大尺寸/间距。 | high（已复核） |
| P2 | `MainActivity.kt:3017` | **#46 搜索框占位文案过长被硬裁，无省略号** | `OutlinedTextField(placeholder=Text("搜索歌曲、专辑、播放列表，也能搜 QQ 音乐"), singleLine=true)`；placeholder 未设 maxLines/overflow，字段被 leading/trailing icon 与内边距吃掉，360dp 屏只剩约 250dp。 | 缩短到一行放得下，或显式 `maxLines=1 + Ellipsis`。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3063` | **#47 曲库搜索失败且 QQ 有结果时，曲库侧错误在页面上完全不可见** | 错误只在 `bothEmpty && errorMessage != null -> ErrorState` 分支出现；`bothEmpty = libraryEmpty && !qqHasResults`；曲库请求失败时 `results==null`，`libraryEmpty=true`，但 QQ 有结果时进入 `else -> LazyColumn`，`if (results != null)` 不成立，`qqSearchSection(libraryEmpty=librarySearchedEmpty)` 也传 false。 | 在 `results==null && errorMessage!=null` 时，即使 QQ 有结果也保留一条曲库失败提示。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3375` | **#48 播放卡片里三行控件硬编码 dp 且滑块起点不齐、数值标签宽度不定** | USB 音量行 `Text("USB 音量", width(88.dp))` + `Text("$usbVolumeLocal%", width(52.dp))`；定时关闭行 `Text("定时关闭")` 后直接 `Slider(weight(1f))`，末尾标签在“约 N 分钟 / 剩余 M:SS / 未开启”之间切换且无固定宽度。两个滑块左缘相差约 12dp。 | 标签宽度进 Dimens，统一两行布局；数值标签固定宽度避免滑块伸缩。 | unverified · 待真机确认 |
| P2 | `MainActivity.kt:3636` | **#49 回收站“清空”按钮禁用时仍是红色，看不出已禁用** | `TextButton(enabled=recycleFiles.isNotEmpty() && !isFileOpsBusy) { Text("清空", color=colorScheme.error) }`；显式 error 色覆盖 TextButton 的 disabled content color；弹窗内“清空（不可恢复）”同理。 | 禁用态不要强制 error 色，改用 TextButton 默认 disabled 配色。 | unverified · 待真机确认 |
| P2 | `QQDownloadScreen.kt:733` | **#50 选曲弹窗曲目列表固定 320dp 上限，窄屏/横屏会超窗裁切** | `LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp))`；外层 AlertDialog 的 text 槽还叠了标题、摘要、已选/全选行、错误文案、`CredentialExpiredCard`，confirm/dismiss 按钮也占高度。 | 320dp 换成按可用空间的比例约束（如 `fillMaxHeight` 或屏幕高度计算）。 | unverified · 待真机确认 |
| P3 | `QQDownloadScreen.kt:265` | **#51 搜索页 QQ 行分隔线全宽，与同页曲库结果行的内缩分隔线不一致** | QQ 分区 `HorizontalDivider(...)` 无内缩（歌曲行、专辑行、QQ 导入页任务行均如此）；同页曲库结果 `SearchResultRow` 的分隔线 `padding(start=48+12dp)`。`QQSectionHeader` 也缺 `SearchSectionHeader` 的 `bottom=4dp`。 | QQ 行分隔线做同样内缩，并补齐区块标题底部留白。 | unverified · 待真机确认 |
| P3 | `MainActivity.kt:3060` | **#52 每次输入都把整片结果（含 QQ 结果）替换成居中转圈** | `when { ... state.isSearching -> CircularProgressIndicator() ... else -> LazyColumn }`；`isSearching=true` 时不渲染 LazyColumn，曲库与 QQ 两区结果整片消失；每次防抖请求都会置 true。 | 搜索中保留上一轮结果，只在结果区顶部显示细进度指示。 | unverified · 待真机确认 |
| P3 | `QQDownloadScreen.kt:324` | **#53 QQ 弹窗圆角与同批其它 AlertDialog 不一致** | `AlertDialog(shape=RoundedCornerShape(Dimens.CornerExtraLarge))` 与 `AlbumPickerDialog` 都显式 20dp；已下载页删除确认框与回收站清空确认框没传 shape（M3 默认 28dp）。 | 统一弹窗圆角：要么都走默认，要么统一 `CornerExtraLarge`。 | unverified · 待真机确认 |
| P3 | `QQDownloadScreen.kt:351` | **#54 用户可见文案里硬编码开发机绝对路径** | `Text("...密钥在电脑上服务启动日志里，或 E:\\nsmic\\server\\.file_ops_key 文件里。")`，把项目开发目录硬编码在 UI。 | 改成与部署路径无关的说法（如“服务端目录下的 .file_ops_key”）。 | unverified · 待真机确认 |
| P3 | `QQDownloadScreen.kt:789` | **#55 曲目勾选行用 2dp 硬编码值做垂直内边距** | `Row(Modifier.fillMaxWidth().toggleable(...).padding(vertical=2.dp))`；2dp 不在 Dimens（SpaceTiny=4dp 起），与 `QQSongRow` 的 `vertical=SpaceSmall` 不一致。 | 换成 Dimens 间距或去掉这层 padding。 | unverified · 待真机确认 |

## 7. 未复核 / 需真机确认清单

以下 62 条为去重后仍为 `unverified` 的行，全部标「待真机确认」。高置信的 #0、#1、#12、#22、#32、#44、#45 不在本清单；已并入其它行的 #56/#69、#57、#66、#67、#73 随宿主条目一起确认。

### 7.1 首页框架（10 条）

- **#2** 迷你条 4 个按钮把歌名挤到约 68dp，空态文案 Ellipsis。
- **#3** 多选条是不透明 Surface，圆角/材质与玻璃岛不一致。
- **#4** MiniPlayerBar 全套尺寸仍是硬编码 dp。
- **#5** 队列标题行约 32dp、移除按钮 40dp（含 #56/#69）。
- **#6** 退出登录紧挨设置、单击立即登出、无确认。
- **#7** 队列分区标题与歌名同用 titleSmall，无层级。
- **#8** AlbumCover 底部渐变固定 32dp，40dp 封面被压暗。
- **#9** 迷你条空态占位是直角灰块。
- **#10** ErrorState/EmptyState 硬编码 dp 且文本对齐不一致。
- **#11** 音量浮窗与多选条共用顶部槽位，多选时被盖住。

### 7.2 歌曲与共享交互（9 条）

- **#13** 长按菜单宽高/内边距/圆角硬编码。
- **#14** 歌曲页空状态未撑满宽度，图标/文案贴左。
- **#15** 歌曲页标题“你的音乐”与页签“歌曲”不一致。
- **#16** 删除用默认/主色，没有 error 警示。
- **#17** 暂停态均衡器条 onSurfaceVariant 对比不足。
- **#18** 改名弹窗三个输入框无滚动，键盘/大字体可能被裁。
- **#19** 多选时分隔线缩进未跟随 Checkbox。
- **#20** 底部 96dp 留白在 10–12 处重复硬编码。
- **#21** 离线提示 labelSmall 比歌手副标题还小。

### 7.3 发现与专辑（9 条）

- **#23** 下拉刷新转圈被状态栏/玻璃岛遮住。
- **#24** 横滑骨架第三张卡被 Row 压窄。
- **#25** 空曲库时每日推荐空态文案错误且与整页空态重复。
- **#26** 整页空态提示的“在设置里刷新曲库”不存在。
- **#27** 专辑网格副标题与标题同色且不限行。
- **#28** 专辑详情离线无提示，摘要按已下载曲目计数。
- **#29** 详情骨架硬编码 16/12/48dp，行内 6dp vs 真实 8dp（含 #57）。
- **#30** “换一批”旋转动画结束时瞬间回正。
- **#31** 专辑详情骨架用封面方块、真实行是序号，缺尾部占位（含 #67）。

### 7.4 艺术家与播放列表（11 条）

- **#33** 艺术家/流派骨架未撑满，居中后加载完瞬移。
- **#34** 组详情骨架只有封面方块，真实卡片多文字区。
- **#35** 播放列表详情头部整片硬编码 dp。
- **#36** 头部曲目数与列表实际行数不同源。
- **#37** 播放列表详情缺少播放全部/随机播放。
- **#38** 已下载内容先闪可点的“下载”（含 #73）。
- **#39** 播放列表副标题未用次要色，和标题同级。
- **#40** 播放列表大封面 8dp 圆角，专辑是 16dp。
- **#41** 失败态 TextButton 强调级低于其它三态。
- **#42** 删除确认后只有按钮变灰，无进度反馈。
- **#43** 离线未下载播放列表仍给“重新加载”死路按钮。

### 7.5 横切问题（13 条）

- **#58** 小展开面板与队列行间距硬编码。
- **#59** 长按菜单阴影安全区两处 16dp 需人工同步。
- **#60** 多选条悬浮偏移/内边距/圆角硬编码。
- **#61** 登录页 24/16dp 裸间距。
- **#62** 玻璃岛/迷你条/Snackbar 外边距各自硬编码 12/8dp。
- **#63** 迷你条进度线 20dp 圆角与面板圆角重复定义。
- **#64** 菜单玻璃高光用 Color.White 字面量。
- **#65** 部分失败重试绕过移动数据确认。
- **#68** 播放列表标题无 maxLines，长名称撑高头部。
- **#70** “换一批”无限旋转动画常驻运行。
- **#71** 播放指示器暂停后仍动画，且逐帧改高度。
- **#72** 专辑网格骨架文字条高度与真实卡不一致。
- **#74** 迷你条控制按钮无媒体时仍可点。

### 7.6 第二批（10 条）

- **#46** 搜索框占位文案过长被硬裁。
- **#47** 曲库搜索失败且 QQ 有结果时，曲库错误不可见。
- **#48** 播放卡片 88/52dp 硬编码值，滑块起点不齐。
- **#49** 回收站“清空”禁用时仍是红色。
- **#50** 选曲弹窗列表固定 320dp 上限。
- **#51** QQ 行分隔线全宽，与曲库结果行不一致。
- **#52** 每次输入都把结果替换成居中转圈。
- **#53** QQ 弹窗圆角与其它 AlertDialog 不一致。
- **#54** UI 文案硬编码开发机绝对路径。
- **#55** 曲目勾选行 2dp 硬编码内边距。

### 7.7 确认重点

- **深/浅色 + 360dp**：#0、#1、#11、#44 必须在手势导航与三键导航两种模式下都检查。
- **大字体/横屏**：#18、#50 的裁切风险最高，1.3 倍字体 + 横屏各走一遍。
- **离线**：#22、#28、#36、#43 覆盖发现页、专辑详情、播放列表详情、搜索。
- **USB 独占音量**：#11 在多选/展开面板下按音量键，确认音量胶囊可见。
- **下载**：#38、#65 在 Wi-Fi 下载→切蜂窝→失败重试路径上确认。
- **动效**：#70、#71 静止时观察是否仍有持续帧/耗电。

## 8. 盲区与风险

### 8.1 盲区

- **字体缩放未覆盖**：本报告只能从固定 dp/sp 推断 #18、#50 的裁切风险，没有 1.3x/1.5x 字体下的实测。
- **横屏未覆盖**：AlertDialog、详情头部、骨架在矮窗口下的表现未知。
- **全屏播放页未覆盖**：`NowPlayingScreen.kt` 未排查；它和首页共用封面/玻璃/内容色，R1 可能同样存在。
- **登录页只覆盖一项**：只有 #61 的间距 token，未做完整视觉排查。
- **无 Compose UI 测试设施**：项目当前没有 UI 测试，所有布局/颜色结论最终靠真机肉眼；本报告 62 条 `unverified` 不能当作已确认事实。
- **行号会漂移**：`MainActivity.kt` 约 4870 行，任何改动都会让行号变化；实施时以组件名和代码锚点定位，不要机械按行号改。
- **导航 inset 因设备而异**：手势导航约 24dp、三键约 48dp，个别 ROM 可能不同；#1/#44 需要至少两种导航模式验证。
- **第二批未排期**：设置/搜索/QQ 导入的问题会继续存在，#44 是 P1，不应久拖。

### 8.2 风险

- **P0 内容色修复会改变浅色主题图标颜色**（黑 → onSurface 近黑），这是预期；但要检查选中 chip 的 `onPrimary` 标签没有被覆盖。`FilterChip` 颜色是显式设置的，风险低。
- **实测 dock 高度有一帧延迟**：首帧 Snackbar 可能使用兜底值。用 `PlayerDockHeight` 作为初始值即可，不要为消除一帧延迟引入 `SubcomposeLayout`。
- **移除上一首/下一首是产品取舍，不是技术必需**：建议单独提交，真机试听一周再决定是否恢复；可先只保留“下一首”一个按钮（歌名回到约 116dp）。
- **音量浮窗改到底部**会改变 USB 独占用户的操作记忆；但当前多选时它根本不可见，底部是净收益。
- **token 清扫涉及 10–12 处 96dp**，机械但容易漏；验收时 grep `96.dp` 应为 0（测试/文档除外）。
- **深色问题必须在真机验证**：浅色主题下“看起来正常”正是它潜伏至今的原因；同时覆盖手势导航与三键导航。
- **长按菜单从 Popup 迁到同窗 overlay 涉及焦点、返回键、点击外部关闭、z 序**，风险高于前几步；`Popup` 的 `focusable=true` 行为要手动补：全屏透明 scrim + `BackHandler` + `pointerInput` 关闭。先做 1 个页面验证，再全量替换。
- **`VisibleLibrary` 增加字段会碰测试**：三个字段给默认 null，避免所有构造点一起改；`OfflineVisibleLibraryTest` 增加“历史曲目被过滤、专辑有一首下载就保留、null 保持 null”三组用例。
- **`MediaDetailScaffold` 是最大改动**：先单独修播放列表的重复头部/maxLines/计数/缺播放按钮，再抽共享骨架；不要一次性重写专辑、播放列表、组详情三页。
- **专辑卡固定两行标题会让短标题卡出现空白**：这是为了网格对齐；若觉得空，可改 `minLines=1` + 固定文字区高度，但后者对大字体不友好。
- **整页滚动会失去固定的播放/随机按钮**：若真机长列表里操作成本明显，再考虑 `stickyHeader` 操作行；本轮不预先做。
- **流派页删除可能碰硬编码导航索引**：`navIndex()`/chip 顺序若按位置硬编码，删项后会错位；改为按 sealed 类型推导，并让 `LibrarySectionTest` 兜底。
- **62 条 unverified 是“待确认”不是“已确认”**：实施前应逐条做代码复核或真机复现，避免把静态推断当 bug 修出回归。

### 8.3 真机验收清单

- 深色/浅色：玻璃岛、迷你条、展开面板、音量浮窗、长按菜单、详情页文字与图标全部可读。
- 360dp：折叠条歌名不被截成 4 个字；队列标题/删除按钮 48dp 可点；多选条与音量同时可见。
- 导航：手势导航与三键导航下 Snackbar 都不压 dock。
- 离线：发现页历史、专辑网格、歌曲页、搜索只显示已下载；详情摘要说明“已下载/共”；离线播放列表无死路重试。
- 播放列表：长名称最多两行；有播放全部/随机；头部加载时不再出现两个头。
- 下载：已下载专辑打开不闪“下载”；蜂窝重试先确认；删除有进度和结果反馈。
- 动效：无播放/无刷新时没有持续旋转；暂停后指示器静止。
- 构建：`"$GRADLE" --no-daemon :app:testDebugUnitTest` 与 `:app:assembleDebug` 通过；UI 无 Compose 测试设施，样式结论以真机为准。