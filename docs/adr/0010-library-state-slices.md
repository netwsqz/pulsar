# ADR-0010：state 按所有权分 slice，组合后仍单一 StateFlow

**状态：** 已接受（2026-11-13）

**相关：** [ADR-0007](0007-downloads-replace-auto-cache.md)（下载不清的边界）、[ADR-0008](0008-offline-shows-only-downloads.md)（离线可见性）、`docs/library-state-refactor.md`

## 背景

曲库界面原先只有一份 36 个平铺字段的 `JellyfinUiState`：

- `JellyfinViewModel` 里有 83 处 `_state.value = ...copy(...)`，登出 / 会话过期 / 回在线各自手抄
  十几到二十几个字段，新增字段必漏；
- 所有曲库加载共用一个 `albumsJob`——进播放列表会取消在途的专辑列表请求；
- 加载编排、三态语义（`null` / 空列表 / 失败）散在 ViewModel 和界面的 `LaunchedEffect` 里；
- 迁移期为了「屏幕一行不改就能编译」，`JellyfinUiState` 一度保留了 36 个只读旧字段与同名
  `copy(...)` 重载（#11 加的兼容层）。

`CLAUDE.md` 原来的约定是「对外只有一条 `StateFlow<JellyfinUiState>`」。拆 slice 看起来与它冲突，
所以先按 `docs/library-state-refactor.md` 的推荐口径确认：**保留单一 StateFlow，只改内部形状**。

## 决定

**`JellyfinUiState` 保留为组合根，内部按所有权拆成三块 slice，对外仍只发一条 `StateFlow`。**

- `SessionState`：登录表单、恢复会话、登录/曲库共用的加载与错误通道；
- `LibraryState`：曲库、发现、详情、搜索、多选、文件操作、离线可见性；
- `DownloadsState`：下载派生的**界面**字段（字节与名单仍归 `DownloadLibrary`，见 ADR-0007）。

配套规则：

- **屏幕直接读写 slice**（`state.library.xxx`）。组合根只有三个字段，旧平铺字段与迁移期兼容层
  已在 #16 删除；`JellyfinUiStateTest` 用反射钉死形状，新增状态必须放进某个 slice。
- **reset 语义归 slice**：`cleared()` / `clearSnapshotBackedScreens()`，约定「字段默认值 = 已清」，
  登出 / 过期 / 回在线不再手抄字段清单。
- **详情页的「当前选中」不是 UI 状态**：导航真相是 `LibrarySection.AlbumDetail(album)`，
  幂等守卫与离线快照兜底收在 `LibraryCache.currentAlbum` / `currentPlaylist`。
- **加载编排归 `LibraryCache`**：每节一份 job + 代次，互不取消（修掉共享 job 的取消 bug）。
- **导航状态可保存**：当前页签 / 详情来源用 `rememberSaveable(stateSaver = LibrarySectionSaver)`，
  转屏与 Activity 重建后不丢。

不引入导航库、不引入 DI 框架；`QQDownloadViewModel` 的字段拆分另开 #31，不混进本次。

## 理由

- **单一 StateFlow 是界面的契约，不是内部结构的契约。** Compose 侧仍然只 `collectAsState` 一次，
  不需要多个 flow 的合并与顺序保证；拆的是「谁拥有哪些字段、谁负责清」，不是发出几路状态。
- **按所有权拆能消掉整类 bug。** 字段默认值 = 已清之后，新增字段自动跟上清理路径；#13 之前
  `expireSession` 与 `signOut` 各抄一份字段清单，已经出现「过期多留几个字段」的不一致。
- **加载与状态分离。** `LibraryCache` 拥有 job / 代次 / 三态，界面只报「进了哪一节」；
  不同节互不取消是明确的修复，不是副作用。
- **兼容层必须有一次性的终点。** 保留旧读法只服务渐进迁移；#16 删掉后只有一种写法，
  新代码不会在两种风格之间摇摆。

## 后果

- `JellyfinUiState` 只有 `session` / `library` / `downloads` 三个字段；所有界面与测试读 slice。
- `LibraryState` 不再有 `selectedAlbum` / `selectedPlaylist`；详情守卫状态在 `LibraryCache` 内部，
  会话切换（`invalidate()`）时一并作废。
- `MainActivity` 顶层页签 / 详情来源改为 `rememberSaveable`；`LibrarySectionSaverTest` 覆盖
  所有分区的保存与恢复。
- 新增状态字段时：先决定属于哪个 slice，再加到那个 slice 上；`StateClearedTest` 的反射夹具
  会提醒补进 reset 场景。
- 风险：组合根仍是单个 data class，slice 数量增加时构造函数会变长。当前只有三块，按所有权
  而不是按页面拆，避免变成「换个地方的大 state」。
