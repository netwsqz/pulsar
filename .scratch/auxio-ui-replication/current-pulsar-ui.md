# Pulsar 现状 UI 调研（只读）

调研日期：2026-10-16　范围：`app/src/main/java/com/example/pulsar/`　**未改动任何项目代码。**
所有路径均为绝对路径，行号取自当前工作区快照（`master`，含未提交改动）。

---

## 1. 发现页数据来源

`DiscoverContent` 定义在 `E:\nsmic\app\src\main\java\com\example\pulsar\MainActivity.kt:1857-2070`。

三个数据源：

| 展示项 | 来源 | 证据 |
|---|---|---|
| 每日推荐 `dailyPicks` | `discoverDailyPicks(tracks, ...)` 纯本地计算 | `MainActivity.kt:1883-1885` |
| 随机专辑墙 | `state.discoverAlbums` | `MainActivity.kt:1971` |
| 曲目池 `tracks` | 调用方传入，实参 `state.tracks.orEmpty()` | 形参 `MainActivity.kt:1860`；调用 `MainActivity.kt:819-832`（`tracks = tracks`，即 `:594`） |

### `discoverDailyPicks`
- 定义：`E:\nsmic\app\src\main\java\com\example\pulsar\DiscoverPicks.kt:12-23`。
- 选曲算法：以 `(dayIndex.hashCode() * 31 + variant)` 为种子对整库 `tracks.shuffled(Random(seed))` 稳定洗牌后 `take(count)`。
  - `dayIndex = LocalDate.now().toEpochDay()`（`MainActivity.kt:1884`），`count = 4`。
  - `variant` 是刷新变体（`picksVariant`，`MainActivity.kt:1882`），下拉刷新 `++`（`MainActivity.kt:1891-1894`）→ 换一批。
  - 纯函数、无 IO，UI 与测试直接调用。

### `tracks`（曲目池）怎么来
- 不在 `loadDiscoverAlbums` 里加载，而是登录/恢复会话时一次性拉全库：
  `JellyfinViewModel.kt:139`（`getMusicItems(...)` → `tracks = tracks`，登录）、`:186-192`（恢复会话）。
- 状态字段：`JellyfinUiState.tracks: List<Track>?`（`JellyfinUiState.kt:25`）。`null` = 会话未就绪（UI 显示灰色波浪骨架，`MainActivity.kt:1918-1923`）。

### `loadDiscoverAlbums()`
- 定义：`JellyfinViewModel.kt:378-384`。
  - 幂等：`if (_state.value.discoverAlbums != null) return`（`:379`）。
  - 请求：`src.getRandomAlbums(userId, accessToken)` → 缓存到字段 `discoverAlbums`（`:381-382`）。
- 刷新版：`refreshDiscoverAlbums()` `JellyfinViewModel.kt:386-391`（无幂等守卫，每次都重发；对应「换一批」按钮 `MainActivity.kt:1958-1968` 和下拉刷新 `:1891`）。
- 数据层：`JellyfinApi.getRandomAlbums(userId, accessToken, limit = 12)`（`data\jellyfin\JellyfinApi.kt:9`）→ `JellyfinDataSource.kt:85-99`，与 `getAlbums` 同一 `/Items` 接口，`SortBy=Random`、`Limit=limit`、`IncludeItemTypes=MusicAlbum`、`Fields=ChildCount`。
- 进入 Discover 页的懒加载触发：`MainActivity.kt:580-582`（`LaunchedEffect(section, sessionReady, state.isOffline)`）。
- 会话切换/离线清空处（会置 `discoverAlbums = null`）：`JellyfinViewModel.kt:117 / 971 / 995 / 1185`。

---

## 2. QQ 下载页结构

文件：`E:\nsmic\app\src\main\java\com\example\pulsar\QQDownloadScreen.kt`（768 行）。
入口 `QQDownloadScreen` `QQDownloadScreen.kt:68-289`：单个 `LazyColumn`（`:92`），`padding(top = GlassIslandTopSpacing)`（`:97`），`contentPadding bottom = 96.dp`，`spacedBy(12.dp)`。自上而下区块：

1. **凭证过期横幅** `CredentialExpiredCard`（`:355-384`）—— 仅 `state.credentialExpired` 时，`item` 在列表最顶（`:101-103`）。标题「QQ 音乐凭证已过期」+ 说明 + 「重试提取凭证」按钮（带转圈）。
2. **错误文字**（`:104-116`）—— `state.errorMessage` 且非凭证过期，红字 `bodySmall`。
3. **服务状态卡** `ServiceStatusCard`（`:334-352`）—— 未连接态（`!serviceReachable || serviceUrl.isBlank()`，`:117-119`）：显示「QQ 下载服务」标题 + 「等待服务器地址…」或「连不上 <url>…」。**这就是未连接态的样式：一张纯文字卡，无输入框可填服务地址**（地址是登录 Jellyfin 后自动推导，见文案 `:340`）。
4. **搜索框**（`:120-142`）—— `OutlinedTextField`，placeholder「搜索 QQ 音乐歌曲、专辑」，前置放大镜图标，有内容时尾部清除按钮；`enabled = state.serviceReachable`。搜索防抖在 ViewModel（300ms）。
5. **音质选择行**（`:144-171`）——「音质」标签 + 4 个 `FilterChip`：Hi-Res / 无损 / 320K / 128K，整行横向可滚。
6. **文件操作密钥卡** `FileOpsKeyCard`（`:293-332`）—— 密码样式输入（可切显示）+「保存」按钮，用于长按菜单的删歌/改名。
7. **曲库信息行**（`:178-187`）—— 条件显示（`serviceUrl` 非空且（未就绪或 root 非空））：「曲库：<root> · N 首」或「扫描中…」。
8. **手动刷新凭证行** `CredentialRefreshRow`（`:387-415`）—— 已连接且未过期时显示，按钮 + 成功反馈文字。
9. **搜索中**（`:192-198`）—— 居中 `CircularProgressIndicator`。
10. **歌曲区**：`QQSectionHeader("歌曲")`（`:417-425`）+ `QQSongRow`（`:433-494`）+ `HorizontalDivider`（`:199-211`）。
    - `QQSongRow` 三态：普通→可下；已在库→图标置灰但仍可点（弹重下确认）+「已在库」灰标签；下载中→禁用 +「下载中」标签。副标题显示可用音质（`qualityLabel` `:762-768`）。
11. **专辑区**：`QQSectionHeader("专辑")` + `QQAlbumRow`（`:497-534`）+ divider（`:212-223`）。
    - `QQAlbumRow`：专辑名/歌手·曲目数·发行时间 + 两个图标按钮（`Checklist` 选曲 / `Download` 整张下载）；入队中显示转圈。
12. **空结果提示**（`:224-234`）—— 有 query、非搜索中、无结果时。
13. **下载任务区**：`QQSectionHeader("下载任务")` + 「刷新」`TextButton` + `QQJobRow`（`:719-759`）+ divider（`:235-250`）。
    - `QQJobRow`：状态图标（转圈/✓/×）+ 标题 + 副标题（排队中/下载中/完成·已入库/失败原因）。
14. **选曲弹窗** `AlbumPickerDialog`（`:546-676`，含内部行 `QQTrackPickRow` `:677-718`）—— `state.albumPicker` 非空时，`AlertDialog` 挂在 `LazyColumn` 之后（`:252-272`）。
15. **重下确认弹窗**（`:273-288`）—— `state.redownloadPrompt` 非空时。

---

## 3. 玻璃岛（顶部悬浮栏）

### `GlassChipBar`
- 定义：`MainActivity.kt:1153-1313`。
- 材质：`liquidGlass(backdrop, surface = surface.copy(alpha = 0.8f))`，`height(56.dp)`，横向 `Row`（`:1186-1197`）。
- 结构：左 = 横向可滚的 chip 组（两侧 16dp 渐变遮罩提示可滑，`:1249-1275`）；右 = 三个固定 `IconButton`：搜索（`:1278-1291`，进搜索页时图标染 `primary`）、设置（`:1292-1305`）、退出登录（`:1306-1311`）。
- **Chips（5 个 `FilterChip`）**：发现(`:1207`)、全部音乐(`:1215`)、专辑(`:1223`)、播放列表(`:1231`)、QQ 下载(`:1239`)。选中态实心 `primary.copy(alpha=0.9f)`，未选中 `surface.copy(alpha=0.4f)`（`glassChipColors` `:1316-1324`）；细描边 `glassChipBorder` `:1327-1331`。
- 选中项 `BringIntoViewRequester` 自动滚入可见区（`:1171-1185`）。
- **出现位置**：仅在非详情页显示——`AnimatedVisibility(visible = !section.isDetail())`（`MainActivity.kt:943-970`），`align(Alignment.TopCenter)`，上下滑淡入淡出。即：Discover/Tracks/Albums/Playlists/Search/QQDownload 六个列表页显示；AlbumDetail/PlaylistDetail/Settings/CachedCopies（`isDetail()`，`LibrarySection.kt:19-23`）隐藏，返回靠系统返回键/侧滑。

### `GlassIslandTopSpacing`
- 定义：`MainActivity.kt:540`，值 **`72.dp`**（`internal val GlassIslandTopSpacing = 72.dp`）。
- 各页据此给内容顶部让位：`DiscoverContent` `:1901`、`TracksContent` `:1350`、`AlbumsContent` `:2092`（-8dp）、`PlaylistsContent` `:2299`（-8dp）、`SearchContent` `:2420`、`QQDownloadScreen.kt:97`；音量浮窗 `:1057`、多选条 `:1065`。

---

## 4. miniplayer

### `MiniPlayerBar`
- 定义：`MainActivity.kt:3150-3568`（424 行）。
- 外层：`Box.liquidGlass(backdrop, surface.copy(alpha=0.55f))`（`:3221-3225`）——注释称这里是**全 App 玻璃材质基准**（`:3219-3220`）。
- 内部 `Column`：
  1. **迷你条主行**（`:3228-3343`）：
     - 整条 `clickable(enabled = controller != null) { onOpenFullScreen() }`（`:3232`）→ **点击打开全屏播放页**。
     - 左右滑手势切歌：左滑下一首 / 右滑上一首，阈值 48dp，带触觉反馈（`:3237-3257`）。
     - 内容：`MiniPlayerCover`（`:3263-3268`，与全屏页大封面同 sharedBounds key，见下）→ 歌名/歌手列（歌名 `basicMarquee` 跑马灯，`:3271-3295`）→ 上一首 / 播放暂停 / 下一首 / 展开箭头 四个 `IconButton`（`:3296-3342`）。
  2. **小展开面板**（`:3346-3536`，`AnimatedVisibility`）：`LiquidGlassSlider` 进度条 + 时间、播放模式切换按钮、输出信息行（`outputLine(outputInfo)`）、可折叠的「播放队列（N 首）」（限高 240dp，长按拖拽排序、移除、点歌跳转）。
- **底部细进度线**（`:3539-3567`）：3dp，`align(BottomCenter)`，专辑主色渐变填充。

### 与 NowPlayingScreen 的衔接
- 打开：点整条 / 面板内按钮 → `onOpenFullScreen` → `nowPlayingSession++` 且 `showNowPlaying = true`（`MainActivity.kt:991-994`）。
- 全屏页：`AnimatedVisibility(visible = showNowPlaying)` + `key(nowPlayingSession)`（`:1017-1045`）包 `NowPlayingScreen`（`NowPlayingScreen.kt`，1013 行）。
- **封面共享动画**：`MiniPlayerBar` 与 `NowPlayingScreen` 都注册同一 sharedBounds key（`NowPlayingScreen.kt:133` 注释：「两侧必须都注册同一个 key 才会匹配上」）；全屏页打开时迷你封面取反隐藏（`fullScreenOpen` 传入 `MiniPlayerCover(visible = !fullScreenOpen)` `:3267`）避免两份封面。
- 关闭：全屏页 `onClose` → `showNowPlaying = false` + `clearLyrics()`（`:1039-1042`）；全屏页打开时返回键先收全屏页（`BackHandler` `:754-756`）。
- 小面板展开状态 `playerExpanded` 上报外部，用于 Snackbar 让位（`:1007`，`:995`）。
- 相关常量：`SEEK_SETTLE_TIMEOUT_MS`、`NOW_PLAYING_FADE_MS`（用于 `:1019-1020`）。

---

## 5. Typography

文件：`E:\nsmic\app\src\main\java\com\example\pulsar\ui\theme\Type.kt`。
**有自定义字体文件**：`E:\nsmic\app\src\main\res\font\` 下 `righteous.ttf`、`poppins_light/regular/medium/semibold/bold.ttf`。
- 标题族 = `RighteousFontFamily`（`Type.kt:20-22`，仅 Normal 一个字重）。
- 正文族 = `PoppinsFontFamily`（`Type.kt:24-30`，Light/Normal/Medium/SemiBold/Bold 五档）。

`PulsarTypography`（`Type.kt:32-138`）规格：

| Style | 字体 | 字号 | 行高 | 字重 | 字距 |
|---|---|---|---|---|---|
| displayLarge | Righteous | 57sp | 64sp | Normal | -0.25sp |
| displayMedium | Righteous | 45sp | 52sp | Normal | — |
| displaySmall | Righteous | 36sp | 44sp | Normal | — |
| headlineLarge | Righteous | 32sp | 40sp | Normal | — |
| headlineMedium | Righteous | 28sp | 36sp | Normal | — |
| headlineSmall | Righteous | 24sp | 32sp | Normal | — |
| titleLarge | Poppins | 22sp | 28sp | SemiBold | — |
| titleMedium | Poppins | 16sp | 24sp | SemiBold | 0.15sp |
| titleSmall | Poppins | 14sp | 20sp | Medium | 0.1sp |
| bodyLarge | Poppins | 16sp | 24sp | Normal | 0.5sp |
| bodyMedium | Poppins | 14sp | 20sp | Normal | 0.25sp |
| bodySmall | Poppins | 12sp | 16sp | Normal | 0.4sp |
| labelLarge | Poppins | 14sp | 20sp | Medium | 0.1sp |
| labelMedium | Poppins | 12sp | 16sp | Medium | 0.5sp |
| labelSmall | Poppins | 11sp | 16sp | Medium | 0.5sp |

---

## 6. Color.kt

文件：`E:\nsmic\app\src\main\java\com\example\pulsar\ui\theme\Color.kt`。

**品牌色**
- `BrandPrimary = Color(0xFF6366F1)`（靛蓝）`Color.kt:11`
- `BrandSecondary = Color(0xFF8B5CF6)`（紫）`:12`
- `BrandAccent = Color(0xFF10B981)`（翠绿）`:13`

**浅色基础色**
- `LightBackground = 0xFFF8FAFC` `:16`
- `LightSurface = 0xFFFFFFFF` `:17`
- `LightSurfaceVariant = 0xFFF1F5F9` `:18`
- `LightOnBackground = 0xFF0F172A` `:20`
- `LightOnSurface = 0xFF1E293B` `:21`
- `LightOnSurfaceVariant = 0xFF64748B` `:22`
- `LightOutline = 0xFFE2E8F0` `:24`
- `LightOutlineVariant = 0xFFCBD5E1` `:25`

**功能色**
- `SuccessGreen = 0xFF10B981` `:28`
- `WarningAmber = 0xFFF59E0B` `:29`
- `ErrorRed = 0xFFEF4444` `:30`

**深色（备用）**
- `DarkBackground = 0xFF0F172A` `:33`
- `DarkSurface = 0xFF1E293B` `:34`
- `DarkOnBackground = 0xFFF1F5F9` `:35`

### Theme.kt（补充）
- `E:\nsmic\app\src\main\java\com\example\pulsar\ui\theme\Theme.kt`：硬编码 `lightColorScheme`（`:14-50`）/ `darkColorScheme`（`:52-82`），**非动态取色（无 dynamicColorScheme）**。`PulsarTheme(darkTheme = false)` 默认浅色（`:84-96`）。
- 另有两个封面取色文件（本次未深入，仅登记）：`CoverPalette.kt`、`CoverTheme.kt`（`rememberCoverGradient`，Coil 加载 96px 小图 + androidx.palette 提色，用于背景渐变/强调色）。

---

## 7. UI 规模分布（MainActivity.kt 各「页面级」Composable 行数区间）

文件 `MainActivity.kt` 共 4044 行。主要 composable 起始行（`@Composable` 行 → 下一个声明前）：

| Composable | 行区间 | 约行数 | 角色 |
|---|---|---|---|
| `PulsarApp` | 286-334 | ~49 | 顶层（主题 + 状态分发） |
| `LoginScreen` | 490-542 | ~53 | 登录页 |
| **`MusicLibraryScreen`** | **543-1152** | **~610** | **主容器**：全部悬浮层/导航/播放接线 |
| `GlassChipBar` | 1153-1313 | ~160 | 顶部玻璃岛 |
| `TracksContent` | 1333-1414 | ~82 | 全部音乐页 |
| `TrackRow` | 1415-1544 | ~130 | 曲目行（5 个列表页共用） |
| `AddToQueueMenu` | 1598-1730 | ~133 | 长按菜单 |
| `TrackFileOpsDialogs` | 1731-1815 | ~85 | 删/改名对话框 |
| **`DiscoverContent`** | **1857-2070** | **~214** | 发现页（每日推荐 + 专辑墙） |
| `AlbumsContent` | 2072-2189 | ~118 | 专辑网格页 |
| `AlbumDetailContent` | 2219-2278 | ~60 | 专辑详情 |
| `PlaylistsContent` | 2279-2398 | ~120 | 播放列表页 |
| `SearchContent` | 2399-2522 | ~124 | 搜索页 |
| `PlaylistDetailContent` | 2578-2638 | ~61 | 歌单详情 |
| **`SettingsContent`** | **2639-2815** | **~177** | 设置页 |
| `RecycleBinSection` | 2816-2910 | ~95 | 设置内回收站 |
| `CachedCopiesContent` | 2911-2984 | ~74 | 已缓存副本页 |
| `MediaCacheSection` | 3026-3099 | ~74 | 设置内缓存管理 |
| `MiniPlayerCover` | 3118-3149 | ~32 | 迷你封面 |
| **`MiniPlayerBar`** | **3150-3568** | **~424** | 底部玻璃播放条 + 小展开面板 |
| `AlbumCover` | 3575-3629 | ~55 | 封面组件（全屏页复用） |
| **`rememberMediaController`** | **3730-3972** | **~243** | 控制器状态轮询（500ms） |

### 本次重构「重灾区」（体量最大 / 耦合最重）
1. **`MusicLibraryScreen`（~610 行，`:543-1152`）** —— 顶层容器，把所有悬浮层、导航状态、播放接线、文件操作、多选、离线态全塞在一处。改动任何一屏几乎都会碰它。
2. **`MiniPlayerBar`（~424 行，`:3150-3568`）** —— 玻璃条 + 进度条 + 小展开面板（进度/模式/输出信息/可拖拽队列）全在一个 composable。
3. **`rememberMediaController`（~243 行，`:3730-3972`）** —— 500ms 轮询 + 队列快照 + 状态聚合。
4. **`DiscoverContent`（~214 行，`:1857-2070`）** —— 双区块（每日推荐 + 随机专辑墙）+ 骨架/空/错三态 + 下拉刷新 + 换批动画，逻辑密度高。
5. **`SettingsContent`（~177 行，`:2639-2815`）** + 内嵌 `RecycleBinSection`（~95）+ `MediaCacheSection`（~74）。
6. **`GlassChipBar`（~160 行，`:1153-1313`）** —— 5 chip + 3 图标 + 滚动遮罩 + bringIntoView。

---

## 未验证 / 存疑

- `QQDownloadScreen` 的「未连接态可填服务地址」：`ServiceStatusCard` 只显示文字说明（`QQDownloadScreen.kt:334-352`），**未见服务地址输入框**；地址来自 ViewModel（登录 Jellyfin 后自动推导）。若期望有手填入口，需再看 `QQDownloadViewModel` 的连接逻辑（本次未展开）。
- `NowPlayingScreen.kt`（1013 行）内部结构本次未逐行展开，仅确认其与 miniplayer 的 sharedBounds key 衔接（`NowPlayingScreen.kt:133`）。
- `CoverPalette.kt` / `CoverTheme.kt` 的取色细节未展开，仅登记存在。
