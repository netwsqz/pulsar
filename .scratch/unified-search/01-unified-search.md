# 01 — 合并搜索：曲库搜索与 QQ 音乐搜索做到一起

**What to build:** 一个搜索页、一个输入框，一次输入同时问曲库和 QQ 音乐。曲库结果照旧分「歌曲 / 专辑 / 播放列表」，其后接「QQ 音乐」总标题下的「歌曲 / 专辑」。QQ 下载不再是顶层页，降级为「设置 → 下载」二级页。

设计决定与理由见 **ADR-0006**（`docs/adr/0006-unified-search-and-download-service-page.md`），术语见 `CONTEXT.md` 的「合并搜索 / 下载服务页 / 下载反馈」。

**Blocked by:** None — can start immediately

**Status:** ready-for-human（实现完成，单元测试全绿；待真机验收）

## 搜索页

- [x] `SearchContent` 接收 `qqState: QQDownloadUiState` 与 QQ 回调（下载单曲、重下确认、整张下载、选曲、选曲弹窗的一系列回调）
- [x] 搜索框 `onValueChange` 同时调 `jellyfinViewModel.updateSearchQuery(q)` 和 `qqViewModel.updateSearchQuery(q)`——两边各跑自己的 300ms 防抖，不新建聚合 VM
- [x] 结果固定顺序：曲库歌曲 → 曲库专辑 → 曲库播放列表 → 「QQ 音乐」总标题 → QQ 歌曲 → QQ 专辑
- [x] QQ 分区**永远**排在曲库之后，不做「曲库为空就提前」的位置漂移
- [x] 曲库为空、QQ 有结果时，在 QQ 分区上方显示一行「曲库中没找到「X」」
- [x] 复用 `QQSongRow`（下载 / 已在库 / 下载中三态）与 `QQAlbumRow`（选曲 + 整张下载两个图标）
- [x] 已在库的 QQ 歌曲照旧显示并标记，点击弹「重新下载？」确认框——**不隐藏、不沉底**
- [x] 「重新下载？」确认框与专辑选曲弹窗挂到 `SearchContent`，状态仍由 `QQDownloadViewModel` 持有；不做全局弹窗
- [x] 搜索页**不放**音质 chip
- [x] 离线提示文案改为「曲库离线 · 只搜手机上的快照；QQ 音乐不受影响」（离线时照常搜 QQ）
- [x] 曲库「未找到相关内容」空状态不能盖住 QQ 结果：只有两边都空才算全空

## QQ 侧的降级与反馈（关键，别踩）

- [x] 搜索页 QQ 分区在「连不上下载服务 / 凭证已过期」时显示一行小字 +「重试」按钮（`retryCredential()` / 重新 `checkHealth()`）
- [x] **绝不**把 QQ 的失败渲染成整页错误——曲库搜到的结果必须照常显示
- [x] `QQDownloadUiState` 拆分错误字段：
  - 搜索期失败（不可达 / 凭证过期）→ 分区内小字常驻（`searchError`）
  - 下载**动作**反馈（音质不可用、入队失败、整专跳过 N 首）→ Snackbar（`actionMessage`）
  - 现状 `handleApiError()` 把两类混写进 `errorMessage`，直接共用会让「电脑没开」把曲库结果盖掉
- [x] `errorMessage` 的 8+ 个写入路径逐个归类；不确定的归 Snackbar 一侧（宁可弹一条，也不让它赖在结果页上）
- [x] 下载完成后**不自动刷新**曲库搜索结果（Jellyfin 文件监控有延迟，立刻重搜多半搜不到）

## 导航与下载服务页

- [x] `LibrarySection.QQDownload` 改名为 `DownloadService`，从 `TopLevelSections` 移除，加入 `isDetail()`
- [x] 顶层 chip 只剩：发现 / 歌曲 / 专辑 / 艺术家 / 流派 / 播放列表
- [x] `QQDownloadScreen` 去掉搜索框与结果列表后，作为「设置 → 下载」二级页复用：服务状态、凭证横幅与重试、默认音质、文件操作密钥、曲库索引信息、任务列表
- [x] 设置页在「播放」与「存储」之间加一行「下载服务 ›」入口（复用 `CachedCopies` 的二级页模式）
- [x] `BackHandler`：`DownloadService → Settings`
- [x] 进入 `DownloadService` 时调 `refreshJobs()`（原来挂在 `LibrarySection.QQDownload` 上）
- [x] 文案收口：`JellyfinViewModel` 的「去「QQ 下载」页检查密钥设置」、`MainActivity` 的「去 QQ 下载页搜歌」→「设置 → 下载」

## TDD 顺序

- [x] 先改 `LibrarySectionTest`：顶层顺序断言、`QQDownload.navIndex()` 改为 `DownloadService` 非顶层 / `isDetail() == true`
- [x] 再改 `QQDownloadViewModelTest`：搜索失败断言 `errorMessage` 的几条改成断言搜索专用字段；新增「搜索失败不写动作反馈字段」的用例
- [x] 最后动 UI（项目无 Compose 测试设施，布局靠真机肉眼验证）

## 验证

- [ ] `:app:testDebugUnitTest` 全绿
- [ ] 真机：搜一个曲库有的词 → 曲库结果在上、QQ 结果在下
- [ ] 真机：关掉电脑上的服务再搜 → 曲库结果照常，QQ 分区显示小字 + 重试
- [ ] 真机：设置 →「下载服务」往返、返回键回设置
- [ ] 真机：QQ 行下载 → Snackbar 反馈；下载中点行内图标显示下载中
