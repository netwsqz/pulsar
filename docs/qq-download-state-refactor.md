# QQ 下载状态拆分（#31）

> 状态：**代码完成（2026-11-18，#31）**，待真机验收。迁移期兼容层已由 #34（2026-11-18）删除。
> 这是 `docs/library-state-refactor.md` §9「待确认 4」的落地：`QQDownloadViewModel` 的 25 字段大 state
> 按曲库那条线（#11–#16）的同一套口径拆成 slice，**独立于曲库状态**。
> 相关：`docs/library-state-refactor.md`、`docs/unified-search-refactor.md`（合并搜索只读 QQ 状态，未改）。

---

## 1. 目标

把 `QQDownloadUiState` 的 25 个平铺字段按**所有权**拆成四块 slice，组合后仍只对外发一条
`StateFlow<QQDownloadUiState>`（ADR-0010）。当时旧读法保留为只读兼容层、各屏行为与测试不变；
兼容层随后已由 #34 删除（见 §5、§8），现在只剩带归属的读法。

## 2. 非目标

- 不改搜索 / 入队 / 轮询 / 凭证的任何行为与文案（唯一例外见 §4）。
- 不拆 ViewModel（仍是一个 `QQDownloadViewModel`），不碰 PC 服务、DataStore 键与接口。
- 不删除兼容层（另开一张票，口径同 #16；见 §8）——**该票即 #34，已完成**。

## 1.1 当前形状（#34 之后）

`QQDownloadUiState` 只有 `service` / `settings` / `search` / `download` 四个字段；
界面与测试直接读 `state.service.xxx` / `state.search.xxx` / `state.download.xxx`。
本文其余各节保留 #31 当时的决策记录，涉及「兼容层」的表述以 §5 / §8 的更新注记为准。

## 3. slice 划分

拆的口径是所有权，不是「哪个页面在用」：

| slice | 字段 | 归属理由 |
|---|---|---|
| `QQServiceState` | `serviceUrl` / `serviceReachable` / `credentialExpired` / `isRetryingCredential` / `credentialMessage` / `libraryReady` / `libraryCount` / `libraryRoot` | 服务**回报**的状态：连通、凭证、曲库索引 |
| `QQSettingsState` | `quality` / `fileOpsKey` / `fileOpsKeyInput` | 用户**设定**的偏好（落盘在 `QQDownloadPreferences`） |
| `QQSearchState` | `query` / `isSearching` / `songs` / `albums` / `error` | 搜索自己的一组状态；错误只有一格（ADR-0006 的两条渲染路径之一）。`searchHadResults` 已随 #34 删除（只写不读） |
| `QQDownloadState` | `jobs` / `downloadingMids` / `inLibraryMids` / `isEnqueuingAlbum` / `enqueuingAlbumMid` / `albumPicker` / `redownloadPrompt` / `actionMessage` | 入队到电脑的任务、标记、弹窗与动作提示 |

命名与位置：

- 搜索 slice 的字段去掉 `search` 前缀（`query` / `error`），与曲库的 `SearchState` 同口径。
- `activeDownloadingMids`（服务端标记 ∪ 本地 queued/running）的派生从组合根搬进
  `QQDownloadState`——「下载中」只允许算一次。
- `AlbumPickerState` 仍留在根包：它是被引用的共享类型，同 `CachedAlbum` 之于 `DownloadsState`。
- **轮询不在状态里**：job + 代次仍归 ViewModel，界面只看 `jobs` 里有没有 queued/running。

## 4. 清理语义

与曲库那条线同一套：

- 每块 slice 的 `cleared()` 直接返回默认实例，**约定「字段默认值 = 已清」**，
  给 slice 新增字段时自动跟上；`QQDownloadStateClearedTest` 用反射夹具钉死覆盖。
- 唯一的生产调用方：`autoFillServiceUrl` 算出的地址与当前不同（**换了一台电脑上的导入服务**）→
  `QQDownloadUiState.cleared(newUrl)`：服务状态、搜索、下载整块作废，只保留 `QQSettingsState`
  与新地址，随后照常探活。
  - **先作废在途工作再清状态**：取消搜索 / 轮询 / 探活 / 凭证的 job 并把代次递增。
    只清状态是不够的——在途协程握着旧 api，回来时代次没变就会把旧服务的搜索结果 / 任务 /
    健康结果写回新服务的状态里（`QQDownloadViewModelTest` 有一条专门用例钉住；
    与曲库那条线「登录 / 过期清缓存 + 代次挡过期协程」同一口径）。
  - 这是本次**唯一的行为变化**：改前换服务会把上一条服务的搜索结果 / 任务 / 已在库标记 / 选曲弹窗
    留在界面上（数据串台）。同一地址（例如端口不同但主机相同）不触发，列表不会清空闪一下。
  - 文件操作密钥虽与那台电脑有关，但它只是落盘偏好的界面镜像；清掉界面反而与盘上的值不一致，
    所以随 `QQSettingsState` 保留。
- `QQSettingsState` **没有** `cleared()`：它没有清理路径（用户偏好不随换服务清）。
  将来真有「重置设置」入口时再加，别提前留一个没人调的空壳。

## 5. 兼容层（已删除，#34）

> **2026-11-18 更新**：迁移期兼容层已在 #34 整段删除。`QQDownloadUiState` 现在只有
> `service` / `settings` / `search` / `download` 四个字段，界面与测试直接读带归属的路径
> （`state.service.xxx` / `state.search.xxx` / `state.download.xxx`）。
> 下面是当时的做法，保留作决策历史。

25 个旧字段曾保留为**构造时求值的只读 `val`**（不是自定义 getter：getter 会让
`if (state.credentialMessage != null)` 之后的智能转换失效，界面现有写法会编译不过）。
删掉它们时读法无需二次改造——本来就写成 `service.xxx` 这类带归属的路径，删除后没有行为变化。

与 #11 的差异：那里连 `copy(...)` 也做了旧参数名兼容；这里调用方只剩 `QQDownloadViewModel`
一个，直接改成写 slice（`updateService` / `updateSettings` / `updateSearch` / `updateDownload`），
少一层过渡代码。构造参数只剩四块 slice，因此 `SearchScreenModelsTest` 的 3 处构造改成 slice 写法
（**读**的地方没动）。

## 6. 测试

- `QQDownloadUiStateTest`（#31 时 4 项；#34 删掉两条兼容层用例后 2 项）：四块 slice 的形状
  （字段名集合恰好四块）、默认实例。
- `QQDownloadStateClearedTest`（4 项）：换服务的清空语义 + 反射夹具覆盖每块 slice 的每个字段
  （新增字段漏清会红）+ 有清理路径的三块 slice 的 `cleared()` 是默认实例。
- `QQDownloadViewModelTest` +3 项：换地址清掉旧服务数据（保留音质、新服务探活照常）；
  同地址不动（端口不同、主机相同）；换服务时**在途**的搜索 / 轮询响应不许写回。
  既有 49 项未改。
- 共享夹具 `test/.../state/QQDownloadStateFixtures.kt`：两份状态测试都用它，别各抄一份。
- 虚拟时间口径不变：轮询用有界 `advanceTimeBy`，探活重查与任务轮询都靠 `settled` / 代次终止，
  没有新增无限周期循环。测「在途响应」时轮询任务用 `done` 收工——用 `running` 会让断言失败
  时把测试卡在 `advanceUntilIdle` 里。

## 7. 验证

- 全量单测 **985 项全绿**（本次新增 11 项），`assembleDebug` 通过。
- 真机待确认：搜索（300ms 防抖）、整张下载、选曲下载、任务进度、凭证过期重试；
  换 Jellyfin 服务器后 QQ 页不残留上一台电脑的数据，音质与密钥仍在。

## 8. 后续

- **删除兼容层 + 屏幕迁 slice**：✅ 已完成（#34，2026-11-18）。
  口径同 #16：屏幕直接读 `qqState.search.xxx` 等，平铺读法整段删除；
  `QQDownloadUiStateTest` 的形状钉子从「按类型筛」收紧成「字段名集合恰好四块 slice」
  （否则删掉兼容层后会退化成恒真）；顺手删掉只写不读的 `QQSearchState.searchHadResults`。
- 页面 Composable 仍收整个 `QQDownloadUiState`（本票只改读法、不收窄参数），
  将来要收窄的话按需再做。
