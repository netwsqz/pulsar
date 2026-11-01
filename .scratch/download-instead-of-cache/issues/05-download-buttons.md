# 05 — 专辑页 / 列表页的「下载」按钮

**What to build:** 专辑详情页和播放列表详情页上那个按钮。点了就下，看得见进度，能取消，能删。

**Blocked by:** 04 — 下载调度器

**Status:** ready-for-human（代码完成）

- [x] 专辑详情页加「下载」按钮（跟现有播放/随机放一起）
- [x] 播放列表详情页同上
- [x] 四态显示：未下载 / 下载中（进度环，可点取消）/ 已下载（点开有「删除下载」）/ 部分失败（「N 首失败 · 重试」）
- [x] **已下载的专辑不显示新增提示**（用户故事 15）——只显示「已下载」
- [x] 删除走「名单里的那批」，不是「当前专辑的全部歌」（用户故事 10）
- [x] **蜂窝流量下弹一次确认**：「这会在移动数据下用约 400 MB」。判断走 `ConnectivityManager`，Wi-Fi 下不弹
- [ ] 空间不足时给明确提示（`IOException` 里的 `ENOSPC` → 「手机存储空间不够」），不是「下载失败」——**降级实现**：`MediaDownloads.download` 只回 Boolean、异常已被吞掉，拿不到 ENOSPC，统一显示「N 首失败」
- [x] 删下载时如果这首歌正在播，**跳过它**（沿用现有「不把正在播的从播放流底下抽走」的约定）

## Comments

### 实现记录（2026-10-25，agent）

**改了什么**

- 新增 `app/src/main/java/com/example/pulsar/DownloadActionButton.kt`：`DownloadActionButton` 可组合 + 一批纯函数
  （`buildDownloadTarget` / `downloadKeysToDelete` / `resolveDownloadState` / `downloadStateSignature` /
  `estimateDownloadBytes` / `downloadProgressFraction` / `isOnCellular`）。
- `MainActivity.kt`：`AlbumDetailContent` 在「播放 / 随机播放」下面加按钮；`PlaylistDetailContent` 在
  标题下方加按钮；两处都新增 `streamUrl` 参数（由 `viewModel::streamUrl` 传入）；`formatBytes` 从 private
  放宽到 internal 供新文件复用。**没有动别人的未提交改动**（专辑页序号列、Apple Music 式布局、QQ 导入改名）。
- `AndroidManifest.xml`：加 `ACCESS_NETWORK_STATE`（普通权限、非运行时授权，只用于判断蜂窝流量）。
- 新增测试 `app/src/test/java/com/example/pulsar/DownloadActionButtonTest.kt`（9 个用例）。

**实现要点**

- **四态按字节真相收口**：调度器状态只覆盖本次运行，所以「已下载」一律逐首问
  `MediaDownloads.hasCompleteCopy`；只有 `Downloading` / `PartiallyFailed` 照抄调度器。
  App 重启后照样显示正确，且不会出现「已下载」之外的「有新增」提示（用户故事 15）。
- **删除按名单删**：`DownloadLedgerStore.entriesFor(serverUrl, userId)` 取 entry 的 `trackIds`，
  逐首 `mediaDownloads.remove(key)`；正在播的那首跳过，且**它的 id 留在名单里**（下次还能删）。
  名单找不到时（迁移失败等）退回「当前列表里已有完整副本的那几首」，不会把服务器新增的歌算进去。
- **账号归属**从 `SessionRepository.load()` 拿 `userId`（工单 08 的「按账号隔离」口径）。
- **蜂窝确认**：`ConnectivityManager.getNetworkCapabilities(activeNetwork).hasTransport(TRANSPORT_CELLULAR)`；
  Wi-Fi / 无网都不弹。体积用 320 kbps（40 KB/s）× 曲目时长估算，只是提示量级。
- **空间不足**：`MediaDownloads.download` 接口只回 `Boolean`，`SimpleCacheMediaDownloads` 内部
  catch 掉了一切异常（包括 `ENOSPC`），所以区分不出「存储不够」和普通失败。**按要求没有改接口**，
  统一显示「N 首失败 · 重试」。要区分得让 `MediaDownloads` 回一个带原因的结果类型（超出本工单范围）。

**验证**

- `:app:testDebugUnitTest`：661 个用例全部通过（含新增 9 个）。
- `:app:assembleDebug`：成功，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- UI 布局本身没写 Compose 测试（仓库没有这套设施），需要真机肉眼验收：
  专辑页 / 列表页按钮位置、进度环、蜂窝弹窗、删除后回到「下载」态。
- 全量跑的时候 `JellyfinViewModelTest.退出登录后重新登录，下载还在且离线能放` 曾失败一次
  （`ledger.entries.size` 期望 1 实得 2），单独重跑与随后的全量跑都通过——是别的工单（07 迁移）
  那边的时序敏感测试，不是本工单的改动，建议其负责人留意。

