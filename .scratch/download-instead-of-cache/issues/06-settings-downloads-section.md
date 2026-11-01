# 06 — 设置页：缓存卡片换成「下载」

**What to build:** 设置页那张「手机缓存」卡片整张换成「下载」，点进去是按专辑分组的下载列表，能整张删、能展开逐首删。**没有清空按钮。**

**Blocked by:** 04 — 下载调度器（要拿得到状态）/ 05 — 按钮（删除入口共用）

**Status:** ready-for-human（代码完成）

- [x] `MediaCacheSection` → `DownloadsSection`：首数 + 占用 + 「已下载」入口
- [x] **删掉「清空」按钮与二次确认弹窗**（铁律 2：没有一键清空）
- [x] 现有「已缓存」子页（`CachedAlbums`）→ 「已下载」：按专辑分组，**可展开逐首删**
- [x] 展开后每首一行，右侧删除图标；删单首走 `MediaDownloads.remove`
- [x] 文案统一用「下载」「已下载」，全页不再出现「缓存」二字
- [x] 空间不足 / 下载失败的提示放在这里可见（用户故事 20）
- [x] `formatBytes` 复用回收站那份，不新写
- [x] **不动**：回收站区块、其他设置项

## Comments

### 实现记录（2026-10-25，agent）

**改动文件**

- `app/src/main/java/com/example/pulsar/MainActivity.kt`
  - `MediaCacheSection` → `DownloadsSection`：标题「下载」，副标题 `N 首 · X GB`（复用 `formatBytes`），
    入口按钮「已下载」；**清空按钮 + 二次确认 AlertDialog + busy/message 提示整段删除**。
  - 新增失败汇总行：从 `PulsarApplication.downloadScheduler.state` 里汇总
    `PartiallyFailed.failed`，显示「有 N 首下载失败 · 去专辑页点「重试」」（用户故事 20）。
  - `CachedCopiesContent` → 标题「已下载」，空态「还没有下载的音乐 / 点专辑或播放列表上的「下载」，就能离线听」；
    去掉 `onOpenAlbum` 参数（整行点击改为展开，不再跳专辑详情）。
  - `CachedAlbumRow` → `DownloadedAlbumRow`：行体点击展开（`rememberSaveable` 记住展开态），
    展开后每首一行（小封面 + 标题 + 歌手 + 删除图标），正在播的那首文字高亮、副标题「正在播放 · 不能删」且删除按钮禁用。
  - 整张删的确认文案改成「删掉这张专辑的下载？」并说明「服务器后来新增的歌不会跟着删」。
  - 全页 UI 文案与描述当前行为的注释里「缓存」→「下载」（含离线提示兜底话术「这首歌没有下载」）。
- `app/src/main/java/com/example/pulsar/JellyfinViewModel.kt`
  - **删除 `clearCache()` 空壳**（连同 `TODO(工单 06)`）及其唯一调用点。
  - `deleteAlbumCopies(album)` 改为**按名单删**：取 `DownloadLedgerStore.entriesFor(serverUrl, userId)`
    里这条 entry 的 `trackIds`，经 `downloadKeysToDelete` 换算成键（跳过正在播的那首）；
    名单里找不到时退回「当前列表里有完整副本的那几首」，绝不按专辑名一锅端。
    防连点改用 ViewModel 内部 `isDeletingDownloads` 标志（不再借 UiState）。
  - 新增 `deleteDownloadedTrack(track)`：逐首删，正在播的直接忽略。
  - 离线提示文案「这首歌没有缓存」→「这首歌没有下载」。
- `app/src/main/java/com/example/pulsar/JellyfinUiState.kt`
  - 删掉 `isCacheBusy` / `cacheMessage`（删 UI 后无引用）；`cacheBytes` / `cacheTrackCount` 保留。
- `app/src/main/java/com/example/pulsar/CachedAlbums.kt`
  - `CachedAlbum(album, cachedCount, totalCount)` → `CachedAlbum(album, tracks: List<Track>, totalCount)`：
    展开逐首删需要曲目本身，`cachedCount` 由 `tracks.size` 得出，不再单独存。
- 测试：`CachedAlbumsTest`（补「展开列表只含已下载的那些」）、`JellyfinViewModelTest`
  （删「空壳 clearCache」用例；新增「整张删按名单删，服务器新增的歌不跟着删」「展开后删单首，正在播的删不动」；
  原有用例断言改成新的 `CachedAlbum` 形状）。

**验证**

- `:app:testDebugUnitTest` 通过（`JellyfinViewModelTest` 94 项、`CachedAlbumsTest` 5 项，全绿）。
- `:app:assembleDebug` 通过。
- UI 未做 Compose 测试（仓库无此设施），需真机肉眼验：设置页卡片文案与数字、已下载页展开/逐首删/整张删、失败汇总行。

**遗留**

- 整张删除后没有回写名单（`DownloadLedger` 里的 `trackIds` 原样留着）。功能上无害——可见性与分组都按字节真相现算，
  下次点「下载」会整条覆写名单——但和专辑页按钮的删除行为（会清名单）不完全一致。
- 空间不足（ENOSPC）与普通失败在 `MediaDownloads.download` 里都被吞成 `false`，卡片上只能统一说「下载失败」。
