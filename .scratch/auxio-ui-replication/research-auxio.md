# Auxio UI 复刻研究（一手资料）

来源：GitHub `OxygenCobalt/Auxio`，默认分支 `dev`，浅克隆到 `C:\Users\netws\AppData\Local\Temp\auxio-src`（经 GitHub API 拉取，git 直连被本地代理挡住）。
本文所有结论均来自实读源码；未验证项已标注。

---

## 0. 最重要的一条：技术栈

**Auxio 是传统 View 架构（XML 布局 + Fragment + RecyclerView/ViewPager2 + ViewBinding + Hilt），不是 Jetpack Compose。**

证据：`app/src/main/res/layout/*.xml`（50+ 个布局文件）、`HomeFragment.kt` / `PlaybackBarFragment.kt` / `PlaybackPanelFragment.kt` 全部继承 `ViewBindingFragment`、`*ListAdapter.kt` 用 RecyclerView。仓库无 `@Composable` 痕迹。

**对复刻的直接后果**：Pulsar 是 Compose，**没有任何布局代码可以照搬**。只能"看它的 XML 布局意图 + 设计 token，用 Compose 重新实现"。好处是 Auxio 的尺寸/颜色/圆角都是显式常量，可以精确抄；坏处是每个组件都要自己写 Compose 版。

- 语言：Kotlin，`language: Kotlin`（GitHub API 确认）
- 许可：**GPL-3.0**（注意：照抄代码有传染性，只抄设计规格/数值/布局思路，别抄代码）
- 用 Material3 **Expressive**（布局里大量 `Widget.Material3Expressive.Button.*`、`SizeOverlay.Material3Expressive.*`）

---

## 1. 导航结构

**没有底部导航栏，也没有侧边栏。是「顶部 AppBar + 可滚动 TabLayout + ViewPager2」。**

`fragment_home.xml` 结构（自上而下）：
1. `CoordinatorAppBarLayout`（style `Widget.Auxio.AppBarLayout`）
   - `MultiToolbar`：内含两个 `AuxioToolbar` —— `home_normal_toolbar`（标题=App 名，menu=`toolbar_home`）与 `home_selection_toolbar`（多选态，返回图标 = `ic_close_24`）
   - `TabLayout`（`home_tabs`）：`tabGravity=start`、`tabMode=scrollable`、透明背景、`tabContentStart=spacing_medium`
2. `home_content` FrameLayout（`AppBarLayout$ScrollingViewBehavior`）
   - `ViewPager2`（`home_pager`），每个 tab 一个 list Fragment
   - 左上角 `home_indexing_container`：卡片式索引进度圈 / 错误图标（本地扫描进度）

`toolbar_home.xml` 菜单项：`action_search`（搜索图标，ifRoom）、`action_sort`（排序图标，ifRoom）、`action_settings`（overflow）、`action_about`（overflow）。

**Tab 是可配置的**（`Tab.kt` + `TabCustomizeDialog.kt` + `TabDragCallback.kt`，支持拖拽排序）：
- `Tab.Visible(type)` / `Tab.Invisible(type)`，用 4 bit 编码存成一个 Int（`SEQUENCE_DEFAULT = 0b1000_1001_1010_1011_1100`）
- `MODE_TABLE = [SONGS, ALBUMS, ARTISTS, GENRES, PLAYLISTS]`，默认 5 个 tab 全可见
- 只有一个 tab 时，TabLayout 隐藏、AppBar 折叠行为关闭（`HomeFragment.setupPager`）

**没有 drawer、没有 bottom nav。** 详情页（专辑/艺术家/流派/播放列表）是独立 `*DetailFragment`，经 Navigation 图 `inner.xml` 跳转。

---

## 2. 首页（Home）——**不是个人化聚合页**

**关键纠正**：Auxio 的"首页"就是**曲库列表页本身**，按 tab 切换 Songs / Albums / Artists / Genres / Playlists。**没有**"最近播放 / 最近添加 / 最常播放 / 随机专辑"这类个人化聚合 section。

证据：`HomeViewModel.kt` 只有 `songList / albumList / artistList / genreList / playlistList` 五个按 sort 排好的全量列表；`HomeGenerator.kt` 的 `songs()/albums()/...` 全部是 `musicRepository.library` 的全量数据套一个 sort。`HomePagerAdapter` 按 `MusicType` 创建 `SongListFragment` 等。

数据来源：`MusicRepository.library`（本地 MediaStore 扫描出的 `musikr` 库对象），配合 `ListSettings` 的排序偏好。空库时 `HomeFragment` 走"选择音乐文件夹"引导（`chooseMusicLocations`）。

> 对 Pulsar 的启示：若 Q5 想要"个人化聚合首页"，**Auxio 没有现成参照**，得自己设计；Auxio 真正可抄的是「顶部可配置 tab + 每 tab 一个列表」这套骨架，以及列表项/封面的视觉规格。

---

## 3. 播放界面（招牌部分）

架构：**两层 BottomSheet 叠放**（`fragment_main.xml`）：
- `playback_sheet`（`PlaybackBottomSheetBehavior`）内含
  - `PlaybackBarFragment`（**miniplayer**，`fragment_playback_bar.xml`）
  - `PlaybackPanelFragment`（**全屏播放面板**，`fragment_playback_panel.xml`，`BottomSheetContentBehavior`）
  - `queue_sheet`（**队列**，`QueueBottomSheetBehavior` + `QueueFragment`，顶部有 drag handle，标题"Queue"）

### 3.1 miniplayer（PlaybackBarFragment + fragment_playback_bar.xml）
- 左：小封面 `CoverView`（`Widget.Auxio.Image.Small`，`spacing_small` 边距）
- 中：歌名（`TextView.Primary.Compact`）+ 艺人（`Secondary.Compact`，marquee 跑马灯）
- 右：`MaterialButtonGroup` = 播放/暂停按钮（`checkable`，图标 `sel_playing_state_24`）+ 次要动作按钮（下一个/循环/随机，三态切换，`playbackSecondaryAction`）
- 底：整条 `LinearProgressIndicator`（`playback_progress_bar`）
- 交互：**点击 → 展开全屏面板**（`playbackModel.openPlayback()`）；**长按 → 进专辑详情**（`detailModel.showAlbum`）

### 3.2 全屏播放面板（PlaybackPanelFragment + fragment_playback_panel.xml）
- 顶：`AuxioToolbar`，**居中标题 "Now playing"**（`lbl_playback`），**导航图标是向下箭头**（`ic_down_24` = 收起面板），副标题居中显示当前队列来源（如 "All songs"）
- 主体：`ViewPager2` 封面轮播（`playback_pager`，方形 1:1，`Corner.Large` 圆角，`CarouselTransformer` 做轮播变换）——**左右滑动切歌**
- 信息区（`playback_info_container`）：歌名 + 艺人，**可点**（点歌名跳当前歌、点艺人/专辑跳详情）
- 控件排（`ScaledPlaybackButtonGroup`）：循环 · 上一首 · 播放/暂停 · 下一首 · 随机（5 键，图标尺寸分级：Middle/Large/Narrow/Large）
- 进度条：`StyledSeekBar`（自定义，带**波形动画** `setWaveEnabled(isPlaying)`，`progress_amplitude=3dp`、`progress_wavelength=40dp`、`progress_wave_speed=20dp`）
- 手势：封面区左右滑切歌（ViewPager2）；封面区**上滑收起面板**（`recycler().isNestedScrollingEnabled=false` + `dampen()`）；快进快退 overlay（`StepperOverlay` / `player_fast_seek_overlay.xml`）

---

## 4. 配色 / 主题 / 字体

### 主题系统（`Accent.kt` + `themes.xml` + `colors_ui.xml`）
- **Material3 完整 colorScheme**（primary/onPrimary/.../surfaceContainerHighest 全套，含 M3 新 token `surfaceDim/surfaceBright/surfaceContainerLow..Highest`）
- **17 个强调色**：Red/Pink/Purple/DeepPurple/Indigo/Blue/DeepBlue/Cyan/Teal/Green/DeepGreen/Lime/Yellow/Orange/Brown/Grey/**Dynamic**
- **Dynamic** = Android 12+（`SDK_INT >= S`）动态取色；旧设备默认 **Blue**（index 5）。`Accent.DEFAULT` 逻辑：S+ 用 dynamic，否则 blue
- 每个强调色还配套一套 **"Black" 变体**（纯黑背景，`Theme.Auxio.*_Black`，见 `Accent.blackTheme`）——即"AMOLED 黑"模式
- 共 16 个显式主题 + dynamic 基色（`grep -c '<style name="Theme.Auxio'` = 16）

**示例色值（Blue 浅色）**：`blue_primary=#37618E`、`blue_background=#F8F9FF`、`blue_surface=#F8F9FF`、`blue_onSurface=#191C20`、`blue_onSurfaceVariant=#43474E`、`blue_outline=#73777F`、`blue_surfaceContainer=#ECEEF4`。可见是**低饱和、偏灰的 M3 tonal palette**，不是鲜艳品牌色。

### 字体（`typography.xml`）
- 全套字体：**Inter**（`@font/inter_regular` / `@font/inter_semibold`），注释明说 "derived from Inter and its size-dependent spacing calculation"
- 每个 M3 TextAppearance 都覆写 fontFamily + **负 letterSpacing**（Display/Headline/Title/Body 为负，Label 接近 0 或正）——这是 Auxio 排版紧凑感的来源
- Display 系列**不使用**（注释：Display styles are not used in Auxio）

### 尺寸（`dimens.xml`）
- 间距：`tiny 4 / small 8 / mid_medium 12 / medium 16 / mid_large 20 / large 24`（dp）
- 触摸目标：`size_touchable_small 48 / medium 56 / large 64 / huge 80`
- 图标：`icon_small 24 / medium 32 / large 40 / huge 48`
- Hero 封面：`hero_small 128 / medium 192 / large 256`
- 圆角：`m3_shape_corners_large 16`、`m3_shape_corners_full 128`；播放面板/封面用 `Corner.ExtraLarge`/`Corner.Large`；**BottomSheet 圆角特调 `20dp`**（注释："badly off-spec but needed to harmonize the corner radii in the playback bar"）
- 进度条波形：`amplitude 3dp / wavelength 40dp / wave_speed 20dp`

---

## 5. 交互逻辑

- **列表项**（`item_song.xml`）：封面 + 主文本（歌名）+ 副文本（info）+ 右侧 `more` 图标按钮；背景 `@drawable/ui_item_bg`；文本色 `sel_selectable_text_primary`（选中态变色）
- **长按**：进入**多选**（`home_selection_toolbar` 显示 "已选 N 项"，AppBar 展开）
- **点 more 图标**：弹 `Menu`（`Menu.ForSong/ForAlbum/ForArtist/ForGenre/ForPlaylist/ForSelection`），经 Navigation 跳 `*MenuDialog`
- **拖拽排序**：tab 配置（`TabDragCallback`）、队列（`QueueDragCallback`）、播放列表详情（`PlaylistDragCallback`）
- **播放列表**：支持新建/导入 M3U/重命名/导出/删除/添加（`PlaylistDecision` sealed class）
- **排序**：每个 tab 有独立排序对话框（`*SortDialog`），升降序
- **搜索**：`fragment_search.xml` 独立页，toolbar 搜索图标进入

---

## 6. 依赖本地媒体库（MediaStore）的部分

**强绑定、搬到 Jellyfin 必须替换或丢弃的：**

| Auxio 概念 | 文件 | 复刻到 Pulsar 的处理 |
|---|---|---|
| `MusicRepository.library` / `musikr` 库 | `music/` 包 | 换成 Jellyfin API 数据 |
| 索引进度 `IndexingState` / 选择音乐文件夹 | `HomeFragment.updateIndexerState`、`chooseMusicLocations` | **丢弃**（Jellyfin 无本地扫描） |
| `MusicType`（SONGS/ALBUMS/ARTISTS/GENRES/PLAYLISTS） | `music/MusicType.kt` | 可保留为导航维度；Pulsar 有 Albums/Playlists，无 Genre/Artist 页 |
| 播放列表 M3U 导入导出 | `PlaylistDecision`、`M3U` | **丢弃**（Jellyfin 播放列表走 API） |
| 存储权限请求 | `storagePermissionLauncher` | **丢弃** |
| `ListSettings` 排序偏好 | `list/ListSettings.kt` | 可保留（本地 DataStore） |

**与数据源无关、可安全照搬的设计**：布局骨架、tab 配置交互、播放 bottom-sheet 双层结构、miniplayer 形态、seekbar 波形、配色/字体/尺寸 token。

---

## 7. 对 Pulsar 的适配提示（结合已定决策）

已定：布局抄 Auxio、皮肤用 Pulsar、发现页+QQ下载页、导航"玻璃岛视觉 + Auxio 导航逻辑"、封面取色保留、操作逻辑本次砍掉、全局统一视觉底座。

**能直接照搬的设计规格：**
1. **尺寸 token**：4/8/12/16/20/24 间距、48/56/64 触摸目标、16/20 圆角 —— 建一套 Compose `Dimens`，全局替换硬编码 dp（Q12 的"统一视觉底座"）
2. **列表项布局**（`item_song.xml`）：封面+主副文本+more 按钮，Pulsar 的 `TrackRow` 可对齐此结构
3. **miniplayer 结构**（封面+歌名/艺人+播放键+细进度条）：Pulsar `MiniPlayerBar` 已是同类，可对齐细节
4. **播放面板骨架**：居中标题 + 下箭头收起 + 方形封面轮播 + 5 键控件 + seekbar —— Pulsar `NowPlayingScreen` 可参照
5. **可配置顶部 tab** 的思路可映射到 Pulsar 玻璃岛 chips

**必须改造的：**
1. **字体**：Auxio 用 Inter，Pulsar 用 Righteous（标题）+ Poppins（正文）。Q3 定了"皮肤用 Pulsar"→ **保留 Pulsar 字体**，但可借鉴 Auxio 的**负 letterSpacing** 让排版更紧凑
2. **配色**：Auxio 是 M3 tonal + 17 强调色 + 动态取色 + 黑主题；Pulsar 是硬编码靛蓝/紫 + **封面取色**（Q10 保留）。融合方向：把 Auxio 的 **M3 完整 colorScheme 结构**（含 surfaceContainer 层级）引入 Pulsar，色相仍走 Pulsar 品牌色/封面取色
3. **导航**：Auxio 是顶部 tab（无底栏），Pulsar 是玻璃岛 chips。Q8 定"玻璃岛视觉 + Auxio 导航逻辑"→ 玻璃岛承担 Auxio TabLayout 的角色，可参考 Auxio 的**可配置/可拖拽 tab** 思路
4. **首页内容**：Auxio 无个人化聚合页（见 §2），Q5 的"个人化聚合首页"**没有 Auxio 参照**，需自行设计；Pulsar 现有"每日推荐+随机专辑"可作起点
5. **数据层**：MediaStore 相关全丢（见 §6），接 Jellyfin

**未验证项：**
- Auxio 各版本（v3/v4/v5）的技术栈差异未逐一比对（本次只读 `dev` 分支当前状态）
- 播放面板的 `CarouselTransformer` 具体变换参数未细读
- "最近播放/最常播放"：`dev` 分支未发现此类 section；不排除其他分支/历史版本有过

---

## 附：关键源文件索引（临时克隆内路径）

```
app/src/main/res/layout/fragment_home.xml          首页骨架（AppBar+TabLayout+ViewPager2）
app/src/main/res/layout/fragment_main.xml          全局骨架（播放 bottom-sheet 双层+队列）
app/src/main/res/layout/fragment_playback_bar.xml  miniplayer
app/src/main/res/layout/fragment_playback_panel.xml 全屏播放面板
app/src/main/res/layout/item_song.xml              列表项
app/src/main/res/layout/item_cover.xml             封面轮播页
app/src/main/java/org/oxycblt/auxio/home/HomeFragment.kt      首页逻辑+tab 配置
app/src/main/java/org/oxycblt/auxio/home/HomeViewModel.kt     首页数据（全量列表）
app/src/main/java/org/oxycblt/auxio/home/tabs/Tab.kt          tab 模型+序列化
app/src/main/java/org/oxycblt/auxio/playback/PlaybackBarFragment.kt    miniplayer 逻辑
app/src/main/java/org/oxycblt/auxio/playback/PlaybackPanelFragment.kt  播放面板逻辑
app/src/main/java/org/oxycblt/auxio/ui/accent/Accent.kt       主题/强调色
app/src/main/res/values/dimens.xml                尺寸 token
app/src/main/res/values/typography.xml            字体规格
app/src/main/res/values/themes.xml                17 主题定义
app/src/main/res/values/colors_ui.xml             M3 色板
```
