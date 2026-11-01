# 04 — 下载调度器

**What to build:** 点「下载」之后真正跑起来的那部分。串行、后台、可取消、失败重试几次再跳过、进度能看。

**Blocked by:** 02 — 下载能力 / 03 — 下载名单

**Status:** ready-for-human（代码完成）

- [x] `DownloadScheduler`：进程级 IO scope + `Mutex` 串行（**一次只跑一张专辑/一个列表**）
- [x] 只下缺的：对名单里每首 `hasCompleteCopy()`，已完整的跳过（取消后重下自动只补缺口）
- [x] 失败重试 3 次（指数退避：1s / 2s / 4s），仍失败则跳过这首，继续下一首
- [x] 进度聚合到 `StateFlow<Map<TargetId, DownloadState>>`（未下载 / 下载中(bytes,total) / 已下载 / 部分失败(n) / 已取消）
- [x] `cancel(target)`：取消协程 + `CacheWriter.cancel()`；**已下完的保留**（用户故事 4）
- [x] 入队时立刻写名单（`trackIds` = 当时全部曲目）；名单只是「打算下哪些」，完成状态现算
- [x] 全部失败不抛异常、不弹错误框（铁律 4），只体现在状态里
- [x] 测试：串行（第二个排队）；重试 3 次后跳过；取消保留已完成；进度聚合；空队列不空转

## Comments

### 实现记录（2026-10-25，agent）

**新建**
- `app/src/main/java/com/example/pulsar/DownloadScheduler.kt`：`DownloadTrack` / `DownloadTarget` /
  `DownloadTargetId` / `DownloadState`（5 态）/ `DownloadScheduler`。
- `app/src/test/java/com/example/pulsar/DownloadSchedulerTest.kt`。

**设计要点**
- `DownloadScheduler` **不依赖 ViewModel**：URL 与 key 由调用方从 `streamUrl(track)` 推导后
  作为 `DownloadTrack` 传进来。调度器只认 `(id, url, key)`。
- 作用域由调用方注入（生产 `PulsarApplication` 的进程级 IO scope，测试 `TestScope.backgroundScope`）。
- 两把锁：`queueMutex` 保证串行；`ledgerMutex` 保证名单的读-改-写不冲突（整表覆写，两个目标
  同时入队会丢条目）。
- 进度只聚合**本次运行**的曲目；本来就有完整副本被跳过的**不进分母**（否则永远到不了 100%）。
- 重试 4 次尝试（首次 + 3 次重试），退避 1s / 2s / 4s。

**接线**
- `PulsarApplication` 末尾追加 `downloadLedgerStore()` 工厂与 `downloadScheduler` lazy 属性。

**验证**
`--rerun-tasks` 全量：**640 个用例 / 0 失败**（含本票新增的 22 个）。

**已知边界**
- `_state` 里取消后是 `Canceled`，但界面重新进页面时不会从字节真相重算「已下载」——
  这一步归工单 05/06（要有地方调 `hasCompleteCopy` 才能算）。

