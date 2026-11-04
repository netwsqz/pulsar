# 13 — 下载状态机与单一入队漏斗

**What to build:** 打开一张已下载的专辑时，按钮先显示不可点的「检查中…」，核对完再显示「已下载」，不再闪一下可点的「下载」。所有下载入口（包括失败重试和继续下载）在蜂窝流量下都先确认；部分失败的重试按钮和下载/已下载一样显眼。删除下载时有进度，删完提示「已删除 N 首」。已下载的专辑不会因为手快被重复入队。

**Blocked by:** 01（设计 token 收口与设置页底部留白）、09（播放列表详情修复）、10（MediaDetailScaffold + 专辑详情迁移）。

**Status:** ready-for-human（代码完成，待真机验收）

**规格参考：** `spec.md` §8

- [x] 打开已下载专辑时按钮先显示禁用的「检查中…」，核对完显示「已下载」，不出现可点「下载」的闪烁。（`resolveDownloadState` 返回 `null` = 检查中，按钮 `enabled = false`；`ByteTruth` 把结论绑在调度器状态签名上，签名一变就作废重查）
- [x] 所有入队入口在蜂窝流量下都先弹确认；Wi-Fi 下不弹。（`DownloadActionButton` 里只有 `requestDownload()` 会入队：首次下载与失败重试共用；确认框的「继续下载」是确认之后才入队）
- [x] 部分失败按钮的强调级与下载/已下载一致，不再是弱化文字按钮。（`TextButton` → `FilledTonalButton` + `Refresh` 图标 + `errorContainer`）
- [x] 删除确认后显示进度；完成后关闭对话框并提示「已删除 N 首」。（详情页走 `onMessage` 回调；已下载列表走 VM state：`isDeletingDownload` + `downloadDeleteMessage`；进度行 `DeleteProgressNotice` 两处共用）
- [x] 检查中/下载中连点不会重复入队。（检查中是禁用按钮；下载中是「取消」；已下载列表的删除有 `downloadDeleteInFlight` 守卫 + 对话框按钮禁用）
- [x] `resolveDownloadState` 测试覆盖调度器状态 × null/true/false，并覆盖 target 为空收口为未下载。（`DownloadActionButtonTest` 新增「五态」「字节还没查完」「字节真相三态」「删除提示」四组用例）
- [x] 构建与单测通过（**710 用例 / 0 失败**，`assembleDebug` BUILD SUCCESSFUL）。
- [ ] 真机验证已下载 / 蜂窝重试 / 删除（待验收，清单见下方评论）。

## Comments

### 实现记录（2026-11-05，agent）

**改了什么**

- **字节真相从 `Boolean` 改成三态**（spec §8「补未知态」）：新增 `resolveAllTracksComplete(target, sessionResolved, hasCompleteCopy)`，
  返回 `null` = 会话还没读出来（不知道这批字节属于谁），没登录 / 空专辑（target 为空）**直接收口为 `false`**，
  不让按钮停在转不完的「检查中…」；`resolveDownloadState` 相应返回 `DownloadState?`，`null` = 检查中。
  调度器核心（`DownloadScheduler`）一行没动——spec 明确「下载只改按钮状态与入队漏斗」。
- **不给旧结论硬撑**：结论连同「算出它时的调度器状态签名」一起存进 `ByteTruth`。签名一变（下载刚写完 / 被取消）
  就作废、回到未知态重查，而不是先拿上一次的 `false` 渲染一帧可点的「下载」（报告 #38 / #73 的另一半）。
- **单一入队漏斗**：`requestDownload()` 成为唯一入口，失败重试从 `::enqueue` 改成它（报告 #65）。
  确认框的「继续下载」仍是确认后入队，不构成第二个漏斗。
- **删除进度与结果**（报告 #42）：确认后对话框里显示「删除中…」（`DeleteProgressNotice`，详情页与已下载列表共用），
  删完关框并报**真删掉的副本数**（正在播的那首跳过就不算它）。详情页经 `onMessage` 走 Snackbar；
  已下载列表的整张删除仍跑在 `viewModelScope`（导航切走不中断，删一半的破坏性操作最难受），
  进度与结果经 `isDeletingDownload` / `downloadDeleteMessage` 回到界面。
- **跨会话不串**：删除按发起时的 `loginGeneration` 守卫——删到一半退出登录 / 会话过期时，
  结果不写进新会话的 state（否则「已删除 N 首」会飘在登录页上）；两个新字段也补进了
  `login()` / `expireSession()` 的重置名单。

**验证**

- `:app:testDebugUnitTest`：**710 用例，0 失败**（新增 6 个；`DownloadActionButtonTest` 9→12，`JellyfinViewModelTest` 91→94）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**（含 arm64-v8a NDK 层）。
- TDD：先写 `resolveAllTracksComplete` / `downloadDeletedMessage` / `Boolean?` 版 `resolveDownloadState` 的用例（红 = 函数不存在、编译失败）再补实现。
- **反向验证过用例不是空跑**：临时把 ViewModel 的会话代次守卫改成 `if (true)`，「删到一半退出登录」用例立刻变红（且**只有它**红），恢复后全绿。

**代码审查（Standards / Spec 并行两轴）后改的地方**

- **Standards 轴**：①两个新 state 字段漏进会话重置名单 → 补 `login()` / `expireSession()`（这是仓库明文的会话约定）；
  ②私有守卫 `isDeletingDownloads` 与 state 字段 `isDeletingDownload` 只差一个字母 → 守卫改名 `downloadDeleteInFlight`；
  ③`allComplete` + `truthFor` 要手工同步 → 合成一个 `ByteTruth(signature, allComplete)`；
  ④删除进度行在 `MainActivity` 与 `DownloadActionButton` 里各写一遍 → 抽成 `ui/components/DeleteProgressNotice.kt`；
  ⑤测试文件顶部注释仍写「四态」→ 改五态。
- **Spec 轴**：①状态矩阵缺 `PartiallyFailed × true` 一格 → 补；②**已下载列表的对话框可能永不关闭**
  （忙碌位若在一帧内跑完，Compose 从没观察到 `true`，`LaunchedEffect(deleting)` 的 key 不变就不重跑）→
  key 加 `deleteStarted`；③同一条删除提示转屏后会再弹一次（`dismiss` 要等 `showSnackbar` 返回）→
  改成**先消费再弹**，且在 `snackbarScope` 里弹（写在 effect 里会被 key 变 null 的重启当场取消）；
  ④`DownloadActionButton.confirmDelete` 没有 try/finally，异常时对话框会永久卡在「删除中…」（连返回键都关不掉）→
  加 finally 收尾，名单写失败静默（与 ViewModel 里 `removeLedgerEntry` 同一口径）。

**已知风险 / 未改**

- **两条删除路径机制不同**：已下载列表走 VM state，详情页按钮走本地 `onMessage` 回调。统一要把详情页的删除
  也接进 ViewModel（它的删除逻辑目前整个在按钮里），超出本单范围（spec「改动范围尽量小」）。
- `downloadDeletedMessage` / `downloadKeysToDelete` 定义在 `DownloadActionButton.kt`（UI 文件）却被
  `JellyfinViewModel` 调用——这个跨层依赖**先于本单存在**（VM 早就在用 `downloadKeysToDelete`），
  本单只是加了一个同类函数，没有搬文件（搬它要连带搬删除逻辑，是更大的重构）。
- **蜂窝确认框的体积仍按整张专辑估**（「约 N MB」）：重试时明显偏大（已下完的不会再下）。
  要精确得知道「哪些还没下」，本单没取这份信息。
- 删除结果只报**首数**，报不出失败：`MediaDownloads.remove` 把异常吞成静默（铁律 4），拿不到失败数。
- 详情页删除仍跳过正在播的那首（既有约定），所以「已删除 N 首」的 N 可能小于名单长度——这是刻意的。

**真机待验清单（360dp × 深/浅色各一遍）**

1. 打开一张**已下载**的专辑：按钮先「检查中…」（禁用），**不出现可点的「下载」**，随后变「已下载」。
2. Wi-Fi 下下载 → 切蜂窝 → 造一次失败 → 点「N 首失败 · 重试」：弹移动数据确认；按钮强调级与「下载 / 已下载」一致（errorContainer）。
3. 详情页点「已下载」→ 确认：对话框显示「删除中…」，删完**自动关闭**并弹「已删除 N 首」。
4. 设置 → 已下载 → 整张删：同样有进度与提示；删完那张卡片从列表消失，设置页的占用数字跟着降。
5. 检查中 / 下载中连点：不重复入队（此时按钮是禁用态或「取消」）。
6. 删除进行中按返回键：对话框**不关**（正在删）；删完自动关。
7. 删除进行中退出登录：登录页上**不该**飘出「已删除 N 首」。
