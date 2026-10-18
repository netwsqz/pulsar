# 05 — 库页重写（Songs / Albums / Playlists）

**What to build:** 把「全部音乐 / 专辑 / 播放列表」三页的视觉按 Auxio 的列表/卡片语言重写——列表项结构（封面 + 主文本 + 副信息 + 更多按钮）、封面网格、间距圆角对齐 Auxio 量纲。删旧换新，不做新旧并存。

**Blocked by:** 01（设计 token 底座）、04（导航壳改造）

**Status:** done（代码完成，真机验收通过 2026-10-18）

- [x] Songs 页按 Auxio 列表项样式（封面 + 歌名 + 副信息 + 更多按钮）
- [x] Albums 页按 Auxio 封面网格/横滑样式
- [x] Playlists 页按 Auxio 列表语言，与库页风格统一
- [x] 保留现有功能：点击播放、加入队列、进入详情
- [x] 加载中骨架、失败错误+重试、空态文案齐备
- [x] 尺寸/圆角取自 01 的设计 token，不散落硬编码 dp
- [x] 真机肉眼验证

## Comments

- **Songs 页**：`TrackRow` 补上右侧「更多」按钮（Auxio `item_song.xml` 的 more 图标位）。
  按钮和长按弹的是**同一个菜单**（`AddToQueueMenu`），不是第二套操作面板——锚点取行右端中部；
  多选模式下隐藏（那时整行是勾选语义）。按钮出现条件沿用 `onAddToQueue != null`，与长按菜单一致。
- **Albums 页**：卡片/网格在工单 06 已抽成 `AlbumGridCard` 并对齐 token，本票只补齐剩余硬编码
  （外边距 16→`SpaceMedium`、间距 12→`SpaceMidMedium`）。骨架小条圆角 4dp 按工单 01 的约定
  保持字面量（组件自身尺寸，与 `TrackRow` 封面 8dp 同理），已加注释说明。
- **Playlists 页**：从「2 列卡片网格」改成**列表语言**（与 Songs / 搜索页同构：封面位 + 名称 +
  「N 首」+ 分隔线），复用 `SearchResultRow`。规格里 Albums 走网格、Playlists 走列表，两页不再是
  两套卡片。骨架同步换成 `TrackListSkeleton` 并 `fillMaxSize`——外层 Box 居中，骨架不撑满会竖直
  居中、加载完从顶部开始就跳。
  **播放列表行没有「更多」按钮**：`SearchResultRow` 本来就没有尾部操作，播放列表也没有「加入队列」
  语义（点进去才是曲目列表）；spec 用户故事 22 只要求「按 Auxio 的列表语言显示」。
- **Songs 页 token 化**：外边距/间距/按钮内边距、`TrackListSkeleton`（48dp 封面位、12dp 间隔、
  分隔线起点）全部改走 `Dimens`。骨架新增 `trailingSpace` 参数给「更多」按钮留位，否则加载完
  文字列会突然变窄。
- **Songs 页没有独立失败态**：`tracks == null` 时整页落到登录表单（`PulsarApp` 的既有逻辑），
  到不了列表页，所以这里只有骨架与空态；Albums / Playlists 的失败重试照旧。
- **功能零改动**：点击播放、加入队列、进详情、长按文件操作/多选、离线标灰、多选条——回调与
  状态机一行未动，只动布局与样式。更多按钮走的仍是父层 `onAddToQueue`，离线没缓存的歌照旧弹
  「没缓存」提示，没有绕过播放守门。
- **测试**：纯布局改动，无 Compose UI 测试设施（项目惯例真机肉眼验证）。
  `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 均通过。
- **未做**：真机肉眼验证（需要用户本人看屏）。详情页（专辑/播放列表详情）与发现页的行样式
  不在本票范围，但它们共用 `TrackRow` / `SearchResultRow`，已随本票自动获得更多按钮与统一量纲。
