# 05 — 库页重写（Songs / Albums / Playlists）

**What to build:** 把「全部音乐 / 专辑 / 播放列表」三页的视觉按 Auxio 的列表/卡片语言重写——列表项结构（封面 + 主文本 + 副信息 + 更多按钮）、封面网格、间距圆角对齐 Auxio 量纲。删旧换新，不做新旧并存。

**Blocked by:** 01（设计 token 底座）、04（导航壳改造）

**Status:** ready-for-human（代码完成，真机肉眼验证待做）

- [x] Songs 页按 Auxio 列表项样式（封面 + 歌名 + 副信息 + 更多按钮）
- [x] Albums 页按 Auxio 封面网格/横滑样式
- [x] Playlists 页按 Auxio 列表语言，与库页风格统一
- [x] 保留现有功能：点击播放、加入队列、进入详情
- [x] 加载中骨架、失败错误+重试、空态文案齐备
- [x] 尺寸/圆角取自 01 的设计 token，不散落硬编码 dp
- [ ] 真机肉眼验证

## Comments

- **Songs 页**：`TrackRow` 补上右侧「更多」按钮（Auxio `item_song.xml` 的 more 图标位）。
  按钮和长按弹的是**同一个菜单**（`AddToQueueMenu`），不是第二套操作面板——锚点取行右端中部；
  多选模式下隐藏（那时整行是勾选语义）。按钮出现条件沿用 `onAddToQueue != null`，与长按菜单一致。
- **Albums 页**：卡片/网格在工单 06 已抽成 `AlbumGridCard` 并对齐 token，本票只补齐剩余硬编码
  （外边距 16→`SpaceMedium`、间距 12→`SpaceMidMedium`、骨架小条圆角 4→`SpaceTiny`）。
- **Playlists 页**：从「2 列卡片网格」改成**列表语言**（与 Songs / 搜索页同构：封面位 + 名称 +
  「N 首」+ 分隔线），复用 `SearchResultRow`。规格里 Albums 走网格、Playlists 走列表，两页不再是
  两套卡片。骨架同步换成 `TrackListSkeleton`，圆角/行高与真实行一致，加载完不跳。
- **Songs 页 token 化**：外边距/间距/按钮内边距、`TrackListSkeleton`（48dp 封面位、12dp 间隔、
  分隔线起点）全部改走 `Dimens`。
- **功能零改动**：点击播放、加入队列、进详情、长按文件操作/多选、离线标灰、多选条——回调与
  状态机一行未动，只动布局与样式。
- **测试**：纯布局改动，无 Compose UI 测试设施（项目惯例真机肉眼验证）。
  `compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 均通过。
- **未做**：真机肉眼验证（需要用户本人看屏）。详情页（专辑/播放列表详情）与发现页的行样式
  不在本票范围，但它们共用 `TrackRow` / `SearchResultRow`，已随本票自动获得更多按钮与统一量纲。
