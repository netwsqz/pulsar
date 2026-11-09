# DownloadLibrary 重构执行计划

> 状态：**进行中（2026-11-09）**。#4 / #5 已完成并本地提交；#6 进行中；#7 未开始。
> 本文是 candidate 4（下载真相只留一个 module）的执行规格，不是 ADR。
> 相关：`docs/session-scope-refactor.md`（账号作用域）、`docs/library-state-refactor.md`（DownloadState slice）。
> ADR 边界：**ADR-0007 决定「音频字节共享、可见性按账号、退出登录不清下载、只有完整副本才算数」**。本方案不改任何一条，只把同一批规则从五处收成一处。

---

## 1. 目标

让「已下载」只有一个判据、一个刷新点、一个删除实现：

- **一个 module**：`DownloadLibrary`。
- **一个真相**：`状态 = 字节完整（byte truth） ∩ 当前账号名单（ledger visibility）`。
- **一个删除流程**：歌还在电脑上、正在播的跳过、名单跟着改。
- **一个串行化点**：名单写入不再由 scheduler / button / ViewModel 各写各的。
- **UI 只画五态**：不再自己查字节、自己算账号、自己写名单。

## 2. 非目标

- 不改 `SimpleCache` / `MediaDownloads` 的字节层实现（只包一层）。
- 不改 `DownloadScheduler` 的串行队列、重试退避、进度聚合语义。
- 不改下载目录 `filesDir/media_cache`、不引入自动淘汰。
- 不改下载 UI 的五态视觉与文案。
- 不引入 WorkManager / 前台服务。
- 不把下载改成「账号私有字节」——字节仍共享（ADR-0007）。

## 3. 现状证据：同一批字节，五种说法

| # | 派生 | 位置 | 口径 |
|---|---|---|---|
| 1 | 字节真相 | `MediaDownloads.kt:102–118` | `totalLength > 0 && isCached` |
| 2 | 离线可见集合 | `JellyfinViewModel.kt:1241–1255` | 字节 ∩ 名单（当前账号） |
| 3 | 专辑按钮五态 | `DownloadActionButton.kt:356–365` | **只看字节**，不查名单 |
| 4 | 本次运行状态 | `DownloadScheduler.kt:71–179` | 下载中 / 部分失败 / 完成 |
| 5 | 设置页占用 | `JellyfinViewModel.kt:1422–1427` | `completeCount()` / `usedBytes()`，**账号无关** |

后果：
- 换账号后，专辑按钮可能说「已下载」，离线可见性却把同一首歌藏起来——两套真相同时成立。
- 按钮的删除兜底 `target.tracks.filter { hasCompleteCopy }`（`DownloadActionButton.kt:163–168`）可能删到另一个账号名下的共享字节。
- 整张专辑删除规则写了两份：`DownloadActionButton.kt:157–198` 与 `JellyfinViewModel.kt:1489–1525`；共享 helper 反而定义在 UI 文件（`DownloadActionButton.kt:368–429`）。
- 名单写入只有 `DownloadScheduler` 持锁（`DownloadScheduler.kt:77–78`）；按钮（`DownloadActionButton.kt:174–179`）与 ViewModel（`JellyfinViewModel.kt:1544–1547`）都是无锁 `load/replaceAll`，存在丢更新。
- `DataStoreDownloadLedgerStore.pruneUnattributed()` 不在接口上，ViewModel 靠向下转型调用（`JellyfinViewModel.kt:118`）；工厂还直接 new 了一份（`JellyfinViewModelFactory.kt:25`），两个构造点。
- `PulsarApplication.mediaDownloads()` 每次返回新 wrapper（`PulsarApplication.kt:107–110`），按钮 / ViewModel / scheduler 各持一份。

## 4. 默认决策

| 编号 | 决策 | 默认 |
|---|---|---|
| D1 | module 形态 | 有状态 class + `StateFlow<DownloadSnapshot>`；内部持有 scheduler / downloads / ledger |
| D2 | 账号作用域 | 引入 `AccountScope(serverUrl, userId)`；暂时由 `SessionRepository` 提供，candidate 6 落地后换成 `SessionScope` |
| D3 | 五态 | `Checking / NotDownloaded / Downloading(progress) / Downloaded / PartiallyFailed(n)`；`stateFor(target)` 输出 |
| D4 | 删除 | 只有一条 `delete(target)`；单曲删除 `deleteTrack(track, key, playingTrackId)` |
| D5 | 名单写 | **已定（#6）**：`DownloadLibrary` 内部一把 `Mutex`；`DownloadScheduler` 不再自己持锁，写名单统一走 library 的入口 |
| D6 | 统计 | `stats()` 返回 `DownloadStats(bytes, completeCount)`；设置页只读 |
| D7 | 调度器 | `DownloadScheduler` 保留为串行 worker；`DownloadLibrary` 组合它，不复制重试/队列逻辑 |
| D8 | 账号隔离 | 字节共享；可见性 = 名单 ∩ 当前账号；删除仍按名单走，正在播的跳过 |

## 5. 目标形状（sketch）

```kotlin
data class AccountScope(val serverUrl: String, val userId: String)

data class DownloadSnapshot(
    /** 字节完整的全部缓存键（共享，账号无关）。 */
    val completeKeys: Set<String>,
    /** 当前账号可见的曲目 id = completeKeys ∩ ledger。 */
    val visibleTrackIds: Set<String>,
    /** 设置页：占用字节 / 完整副本首数。 */
    val stats: DownloadStats,
    /** 每个 target 的本次运行状态。 */
    val running: Map<DownloadTargetId, DownloadState>,
)

data class DownloadStats(val usedBytes: Long, val completeCount: Int)

sealed interface DownloadActionState {
    data object Checking : DownloadActionState
    data object NotDownloaded : DownloadActionState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadActionState
    data object Downloaded : DownloadActionState
    data class PartiallyFailed(val failed: Int) : DownloadActionState
}

class DownloadLibrary(
    private val downloads: MediaDownloads,
    private val ledger: DownloadLedgerStore,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<DownloadSnapshot>

    /** 当前账号作用域变化时调用（登录 / 切号 / 退出）。 */
    fun setAccount(scope: AccountScope?)

    fun stateFor(target: DownloadTarget): StateFlow<DownloadActionState>
    fun enqueue(target: DownloadTarget)
    fun cancel(target: DownloadTarget)
    fun delete(target: DownloadTarget, playingTrackId: String?): DownloadDeleteResult
    fun deleteTrack(track: Track, key: String, playingTrackId: String?): Boolean
    fun refresh()
}
```

### 5.1 落地时确认的差异（#4 / #5 / #6 实测）

> **最重要的一个坑**：缓存键是**播放流 URL**，名单存的是**曲目 id**，两套 key 对不上。
> 所以「字节 ∩ 名单」不能直接相交，必须先把曲目映射成缓存键。落地时改成：
>
> ```kotlin
> suspend fun refresh(tracks: List<Track>, cacheKeyOf: (Track) -> String?): DownloadSnapshot
> ```
>
> - `tracks` 是「当前可能出现在界面上的全部曲目」，`cacheKeyOf` 由调用方给（生产是 `viewModel::cacheKey`）；
> - 映射和相交**都在 module 里算**，ViewModel 不再保留第二份交集——否则「一个真相」当场作废；
> - 曲目算不出 URL（离线 / 未登录）→ 该曲目不可见，不猜。
>
> 另外两处与草图不同：
> - `stateFor` 返回的是缓存过的 `StateFlow`，不是每次新建——否则每个按钮一个 flow，重组时抖动；
> - `delete` / `deleteTrack` 返回 `DownloadDeleteResult(deletedCount, ledgerUpdated)` 由 `DownloadLibrary` 组装，
>   UI 只负责弹「已删除 N 首」。

设计要点：
- `stateFor` 需要知道目标曲目表才能查字节；`target` 已包含 `tracks`（`DownloadTrack.key`），所以够了。
- `delete` 返回 `DownloadDeleteResult(deletedCount, ledgerUpdated)`，UI 用它弹「已删除 N 首」。
- 正在播的曲目：`delete` / `deleteTrack` 都跳过，并保留名单条目。
- 名单写入：`DownloadLibrary` 提供 `private suspend fun updateLedger {}`，scheduler 的 `recordInLedger` 通过同一个入口（避免两把锁）。
- `setAccount` 后重算 `visibleTrackIds` 与 `stats`；退出登录不清字节（ADR-0007）。

## 6. 执行步骤

### Step 0 — 基线 + 行为矩阵

1. 跑全量单测。
2. 列出当前五个派生各自的测试覆盖：
   - `MediaDownloadsTest`（字节层，5 个）
   - `DownloadSchedulerTest`（9 个）
   - `DownloadActionButtonTest`（12 个纯函数）
   - `DownloadLedgerTest`（14 个）
   - `JellyfinViewModelTest` 下载/离线相关用例
3. 画一张「输入 → 期望输出」矩阵，作为新 module 的测试种子。

**验收**：知道哪些行为已有测试、哪些是空白。

### Step 1 — `DownloadLibrary` 骨架 + 账号作用域

1. 新增 `app/src/main/java/com/example/pulsar/download/DownloadLibrary.kt`、`DownloadModels.kt`。
2. 构造函数注入 `MediaDownloads` / `DownloadLedgerStore` / `DownloadScheduler` / `CoroutineScope`。
3. 实现 `setAccount(scope)` + `refresh()`：`completeKeys`、`visibleTrackIds`、`stats`。
4. `JellyfinViewModel.computeDownloadedTrackIds()` 改成从 `DownloadLibrary.state.value.visibleTrackIds` 读。
5. 旧 `MediaDownloads.completeKeys()` 调用暂时保留，作为 library 的内部实现；不要有第二套订阅。

**TDD**
- RED：`DownloadLibraryTest`：
  - 在线 / 空名单：`visibleTrackIds` = 空集（名单不可用时不筛？注意：ADR 口径是名单可用才交集；名单未注入时退化为只看字节——保留这个兜底）。
  - 字节完整但不在名单：不可见。
  - 名单里有但字节不完整：不可见。
  - 换账号：可见集合跟着换。
  - `stats` 账号无关。
- GREEN：实现。

**验收**：离线可播集合与改前一致；测试全绿。

### Step 2 — 五态与删除收进 library

1. `DownloadActionButton` 的 `resolveDownloadState` / `resolveAllTracksComplete` / `confirmDelete` / `openDeleteConfirm` 改为：
   - `val state by library.stateFor(target).collectAsStateWithLifecycle()`
   - 删除：`library.delete(target, currentTrackId)`
   - 蜂窝确认仍在 Composable（UI 关注点），但入队走 `library.enqueue(target)`。
2. 删除 `DownloadActionButton` 里的 ledger 读改写与字节删除逻辑；只保留 UI 状态（对话框、转圈）。
3. `JellyfinViewModel.deleteAlbumCopies` 改为 `library.delete(...)`；保留 `isDeletingDownload` / `downloadDeleteMessage` 的界面状态，或者由 library 发事件。
4. 单曲删除也走 library。

**TDD**
- RED：`DownloadActionState` 五态矩阵（沿用 `DownloadActionButtonTest` 的 12 个用例）；
- RED：删除只删名单那批、跳过正在播、更新名单、返回正确计数；
- RED：字节完整但不在名单 → 不显示「已下载」（账号隔离）；
- GREEN：实现。

**验收**：专辑页/播放列表页按钮五态与改前一致；换账号后不再出现「按钮说已下载、离线看不见」。

### Step 3 — 入队 / 取消 / 统计统一入口

1. `DownloadLibrary.enqueue` / `cancel` 包 scheduler；
2. `DownloadsSection` 的失败聚合改读 `library.state`；
3. `PulsarApplication` 只保留一个 `downloadLibrary` 单例；`mediaDownloads()` / `downloadScheduler` 不再被 UI 直接拿。
4. `DownloadActionButton` 不再从 `PulsarApplication` 拿四个依赖。

**TDD**
- RED：入队写名单一次；重复入队取消旧任务；取消保留已下字节；
- RED：统计随完成/删除刷新；
- GREEN：实现。

**验收**：`grep -rn "application.downloadScheduler\|application.mediaDownloads()\|application.downloadLedgerStore()" app/src/main/java` 只出现在 `PulsarApplication` 自己的构造里。

### Step 4 — 名单串行化 + 接口收口

1. 把 `DownloadScheduler.recordInLedger` 的 `ledgerMutex` 逻辑收进 `DownloadLibrary` 的 `updateLedger`，scheduler 通过回调/接口写入。
2. `DownloadLedgerStore` 增加 `pruneUnattributed()`（接口方法），删除 ViewModel 的向下转型；工厂统一用 `application.downloadLedgerStore()`。
3. 补测试：并发两个 writer 不丢条目（用 `runTest` + 两个 coroutine）；prune 只删空归属。
4. 删除 `DownloadActionButton` 里的纯函数副本（如果测试还在，迁到 `DownloadLibraryTest` 或保留为内部 helper）。

**验收**：名单写入只有一个 `Mutex`；没有 `as? DataStoreDownloadLedgerStore`。

### Step 5 — 清理 + 文档

1. 删除旧的 `resolveDownloadState` / `resolveAllTracksComplete` / `downloadKeysToDelete` / `downloadDeletedMessage`（或移到 library 内部并标注 internal）。
2. 更新 `CLAUDE.md` 下载章节：加 `DownloadLibrary` 的职责与「一个真相」红线。
3. 更新 `CONTEXT.md`：**下载真相（Download truth）** 现有定义可复用；如新增 `DownloadLibrary` / `DownloadSnapshot` 术语，写进去。
4. 全量测试 + `assembleDebug` + 真机清单。

## 7. 测试策略

- `DownloadLibraryTest` 成为新的主测试面：五态、账号隔离、删除、名单串行化、统计。
- 现有 `DownloadSchedulerTest` 保留（scheduler 仍是独立 module）。
- `MediaDownloadsTest` 保留（字节层）。
- `DownloadActionButtonTest` 的纯函数用例迁入 `DownloadLibraryTest`，旧的删除或保留为内部实现测试。
- 不新增 Compose 测试。

## 8. 真机验收清单

- [ ] 专辑详情：下载 → 进度环 → 已下载；杀进程重进仍显示已下载
- [ ] 播放列表详情：同上
- [ ] 下载中取消 → 只补缺口；再点继续
- [ ] 部分失败 → 「N 首失败 · 重试」
- [ ] 已下载点开 → 删除确认 → 进度 → 「已删除 N 首」
- [ ] 正在播的那首不会被删；删完还能播
- [ ] 换账号：上一个账号的下载不可见；按钮不显示「已下载」
- [ ] 设置页「已下载」列表与专辑按钮一致
- [ ] 设置页占用字节 / 首数与实际一致
- [ ] 退出登录：下载不丢（ADR-0007）
- [ ] 离线：已下载列表可播；未下载提示
- [ ] 蜂窝流量点下载 → 确认框；Wi-Fi 不弹

## 9. 风险与待确认

| 风险 | 缓解 |
|---|---|
| 把 scheduler 包进 library 后，进度回调频率导致 StateFlow 抖动 | `stateFor(target)` 只映射当前 target；进度更新用 `distinctUntilChanged` / 同一个 snapshot |
| 账号切换时字节共享导致删除越权 | 可见性用名单；删除入口只列名单里的 target；`delete` 不接受任意 key |
| 名单写入迁移期间两把锁 | Step 4 前先只读；写路径逐步收口，最后删旧锁 |
| `DownloadActionButton` 的 UI 状态（对话框）与 module 状态混淆 | module 只管下载状态；对话框/蜂窝确认留在 Composable |
| ADR-0007 的「退出登录不清」被误改 | 测试钉死：signOut 后 `completeKeys` 不变、`visibleTrackIds` 清空 |

**待确认**
1. `DownloadLibrary` 是否持有 scheduler，还是 scheduler 持有 library？推荐 library 持有 scheduler（UI 只认 library）。
2. `stateFor(target)` 返回 `StateFlow` 还是 `StateFlow<Map<targetId, state>>`？推荐后者，避免每个按钮一个 flow。
3. 账号作用域先接 `SessionRepository` 还是直接等 candidate 6？推荐先接 `AccountScope` 值类型，candidate 6 落地后换来源。
4. 换账号后按钮的「已下载」是否应该完全隐藏，还是显示「不可见」？按 ADR-0007，推荐当作未下载（NotDownloaded）。

## 10. 新窗口开工提示词

```
按 docs/download-library-refactor.md 执行。先做 Step 0 基线 + 行为矩阵，再做 Step 1：
新增 download/DownloadLibrary.kt 与 DownloadModels.kt，把 JellyfinViewModel 的
computeDownloadedTrackIds 改为从 library 的 visibleTrackIds 读。先写 DownloadLibraryTest
（账号隔离、字节完整但不在名单、名单有但字节不完整、stats 账号无关）再实现。
完成后跑全量单测 + assembleDebug，报告改动与验证结果。
```

## 11. 回退策略

| Step | 怎么退 |
|---|---|
| Step 1 | `computeDownloadedTrackIds()` 改回 `legacyComputeDownloadedTrackIds()`（旧实现保留为 fallback，本步就是为它留的） |
| Step 2 | `DownloadActionButton` 恢复旧的 `resolveDownloadState` / 本地 ledger 读写；`library.stateFor` 不接线 |
| Step 3 | UI 改回直接拿 `application.downloadScheduler` / `mediaDownloads()` |
| Step 4 | 名单写回 scheduler 自己的 `ledgerMutex`；`pruneUnattributed` 恢复向下转型 |
| Step 5 | 只删代码/改文档，回退即恢复上一 commit |

关键：**每一小步都保留旧实现不删**（Step 5 才统一删），所以任何一步都能单独退。
