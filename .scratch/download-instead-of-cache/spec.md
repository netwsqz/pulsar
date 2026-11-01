# 规格：下载取代自动缓存

**Status:** ready-for-agent

**取代：** `.scratch/library-cache/spec.md`（那份规格里的「自动缓存 + 5 GB 淘汰」已废，其余铁律与快照部分继续有效）

**决策记录：** [ADR-0007](../../docs/adr/0007-downloads-replace-auto-cache.md)

## 问题陈述

手机本地缓存（工单 01-06，已上线）是**自动**的：听一首歌就顺手留一份副本，攒到 5 GB 按最久没听淘汰。用下来的问题是意图和行为对不上：

- **攒了一堆没打算留的歌**，用户不知道是哪来的
- **想离线听某张专辑反而要先联网听一遍** —— 需求是「提前存」，能力只有「事后留」
- 5 GB 的 LRU 会在用户不知情时删歌，删的还是「最近没听的」，可能正是留着备用的

一句话：**用户不知道手机上有什么，也控制不了它。**

## 解决方案

**把「自动留」换成「手动存」。**

- 专辑详情页、播放列表详情页各一个「下载」按钮
- 存下来的**永远不会自己消失**：没有自动淘汰、没有总量上限
- 播放链路**纯只读**：听歌一个字节都不写盘
- 手机上有什么、占多少，全在设置的「下载」页里看得见、删得掉

## 名词

定义见 `CONTEXT.md`：**下载 / Download**、**下载名单 / Download ledger**、**已下载列表 / Downloaded view**、**曲库快照 / Library snapshot**。别再用「缓存」这个词指代这些东西。

## 铁律

**1. 播放绝不写盘。** 缓存层只能加速读取，不能产生任何新文件。判断标准：听完一整张专辑，手机存储占用不变。

**2. 什么都不自动删。** 没有 LRU、没有上限、没有「一键清空全部」。用户的下载只有用户能删。

**3. 已下载 = 字节真相，不是名单真相。** 名单里记着不算数，字节不全就是没下载。

**4. 下载失败不影响播放，也不弹错误框。** 失败自动重试几次再跳过，结果在下载按钮上如实显示（N 首失败 + 可重试）。

**5. 下载只动手机本地。** 删下载、清名单都不碰电脑曲库，PC 服务没开、完全没网照样成功。

**6. 不引入新权限、不引入新依赖。** `CacheWriter` / `CacheDataSource` / `SimpleCache` 全是 media3 1.5.1 自带的。

**7. 存量一首不删。** 升级时老副本就地变成「下载」，离线能力不归零。

## 用户故事

### 下载

1. 作为使用者，我想在专辑详情页点一下就把整张专辑存到手机上，不用一首一首来。
2. 作为使用者，我想在播放列表详情页做同样的事。
3. 作为使用者，我想看到下载进度，而不是点了之后不知道在干嘛。
4. 作为使用者，我想能取消下载；已经下完的那几首留着（下次点只补剩下的）。
5. 作为使用者，我想下载在后台继续跑——切页面、按 home、息屏都不打断。
6. 作为使用者，我想在蜂窝流量下点下载时被问一次「这要用约 400 MB 移动数据」，而不是默默跑掉。
7. 作为使用者，我想中间某一首失败了自动重试几次，还不行就跳过继续下别的，最后告诉我结果。

### 看得见、删得掉

8. 作为使用者，我想在设置里看到手机上下载了多少首、占了多少空间。
9. 作为使用者，我想进「下载」页按专辑看，并且能整张删或展开逐首删。
10. 作为使用者，我想删下载时**就只删当初下的那批**——服务器后来新增的歌不会因为我删这张专辑就跟着没了。
11. 作为使用者，我想知道删的是手机上的副本，电脑上的歌一首没动。

### 离线

12. 作为使用者，我想离线时曲库各屏**只显示已下载的东西**——没下载的不出现在列表里，而不是灰着占地方。（口径见 ADR-0008，原「全量 + 标灰」已废弃）
13. 作为使用者，我想离线时点开一个已经下载过的播放列表，能正常看到并播放它。
14. 作为使用者，我想下载了一半（App 被杀）的歌不算「已下载」，免得点进去听一半断掉。
15. 作为使用者，我想在服务器上给已下载的专辑加了新歌之后，界面上**不啰嗦**——要补就删了重下。

### 账号与升级

16. 作为使用者，我想退出登录时下载**不被清掉**（那是我主动存的）。
17. 作为使用者，我想换账号后看不到别的账号的下载——但它们占的空间我得知道，并且能清。
18. 作为使用者，我想升级 App 后，之前自动攒下的副本直接变成「下载」，一首不丢。

### 不出事

19. 作为使用者，我想下载出任何问题都不影响我正常听歌——只是退回到网上直接播。
20. 作为使用者，我想空间不够时看到一个明确的提示，而不是失败得莫名其妙。

## 实现决策

### 播放链路改只读

`PlaybackService.cacheDataSourceFactory()` 加 `.setCacheWriteDataSinkFactory(null)`，`CacheDataSource.Factory` 内部 `cacheIsReadOnly` 置 true，write data source 变 null。

**bytecode 已确认（media3 1.5.1）**：`createDataSourceInternal` 在 `cacheIsReadOnly == true` 或 upstream 为 null 时把 `cacheWriteDataSource` 置 null；`openNextSource` 只在上游打开分支才用写 sink。所以只读模式下命中读本地、未命中走网络，不写一个字节。

### 淘汰器换掉

`PulsarApplication.mediaCacheStore` 里 `LeastRecentlyUsedCacheEvictor(5 GB)` → `NoOpCacheEvictor`。`kMediaCacheMaxBytes` 常量删掉。

**注意**：`LeastRecentlyUsedCacheEvictor` 在 1.5.1 里是 `final`，本来也继承不了，正好不用纠结。

### 下载器

用 media3 自带的 `CacheWriter`（1.5.1 dex 确认存在）：

```kotlin
CacheWriter(
    factory.createDataSourceForDownloading(),
    DataSpec.Builder().setUri(url).setKey(key).build(),
    null,
    progressListener
).cache()   // 同步阻塞，放 IO dispatcher
```

- `CacheWriter` 跳过已有 span，所以「取消后重新点」天然只补缺的部分
- 取消 = `CacheWriter.cancel()`（它有这个方法）；或直接 cancel 协程
- **token 走 header 不进 URL**：`CacheDataSource` 的上游仍是 `AuthHttpDataSourceFactory`，这一点跟现在一样

### 下载调度：串行 + 后台

一个进程级协程（挂 `PulsarApplication.playbackReportScope` 那样的 IO scope），内部 `Mutex` 串行。

- 不用前台服务、不用 WorkManager（ADR-0007 已记录取舍）
- App 被杀 = 下到一半，重进按字节真相显示为「未下载」
- 进度通过 `CacheWriter.ProgressListener` 回调到 `StateFlow`

### 下载名单存储

DataStore + JSON（跟 `SilenceSkipPreferences` / `LoudnessPreferences` 同套路）：

```
DownloadLedger {
  entries: List<DownloadEntry>
}
DownloadEntry {
  serverUrl: String
  userId: String
  kind: "album" | "playlist"
  id: String
  name: String
  trackIds: List<String>      // 当初下载的那批
  createdAt: Long
}
```

**名单里不存「完成状态」**——完成状态一律现算（字节真相）。名单只回答「当初下了哪些」。

### 部分完成的批次怎么记账

用户点了下载 → 立刻写名单（`trackIds` = 当时的全部曲目）→ 逐首下。
- 中途取消/被杀：名单留着，未下完的字节不全 → 界面按字节真相显示为部分已下载 / 未下载
- 再点一次：对名单里的曲目逐个 `hasCompleteCopy()`，只下缺的

**这是名单真相与字节真相的分工点，别搞混**：名单决定「删什么、补什么」，字节决定「算不算已下载」。

### 迁移（存量副本 → 名单）

首次启动到新版本时跑一次：

1. `cache.completeKeys()` 拿全部完整副本的 key
2. 反查：key 是播放流 URL，从中提取 trackId（URL 里 `Videos/{id}/stream`），跟当前曲目表比对 → `Track` → `album`
3. 按专辑归组写进名单（`createdAt` = 迁移时间）
4. 反查不到的（换过账号、歌已删）→ 单独一条 `kind = "orphan"`，界面上进「其他」组，可逐首或整组删

**迁移只跑一次**（DataStore 里一个 `migrationDone` 标记）。跑失败不重试、不留半截：名单写不进去就当没有旧副本（字节还在，用户下次播放能放——但因为播放只读，它不会被「认领」，所以界面上看不见）。**这个降级是可接受的**（铁律 4 的同类），但要打日志。

### 接口与缝

`MediaCache` 改名 `MediaDownloads`（语义变了，名字跟着改）。方法：

| 方法 | 说明 |
|---|---|
| `hasCompleteCopy(key)` | 不变，字节真相 |
| `usedBytes()` / `completeCount()` | 不变 |
| `completeKeys()` | 不变 |
| `download(url, key, onProgress): Result` | 新的，走 `CacheWriter` |
| `remove(key)` | 不变 |
| ~~`clear(keepKeys)`~~ | **删掉**——没有「一键清空」了 |

新增 `DownloadLedgerStore`（接口 + DataStore 实现），跟 `SessionRepository`/`SessionStore` 那套一致。

### 下载调度器

`DownloadScheduler`（新文件）：持有 `MediaDownloads` + `DownloadLedgerStore` + `JellyfinApi`（拿 URL）。
- `enqueue(target: DownloadTarget)`、`cancel(target)`、`observe(): StateFlow<Map<TargetId, DownloadState>>`
- 串行、进度聚合、失败重试（3 次指数退避后跳过）

### UI

- **专辑详情页**：现有的播放/随机按钮旁边加「下载」。四态：未下载 / 下载中（进度环 + 可点取消）/ 已下载（可点删除）/ 部分失败（提示 N 首失败，可重试）
- **播放列表详情页**：同上
- **设置页**：`MediaCacheSection` → `DownloadsSection`。首数 + 占用 + 「已下载」入口。**没有清空按钮**
- **已下载页**（现有 `CachedAlbums` 改）：按专辑分组，展开逐首删
- **QQ 入口改名**：`LibrarySection.QQDownload` 的文案 → 「QQ 音乐导入」（只改文案，不动结构）

### 不动的东西

- **Jellyfin 数据层**：一行不改
- **播放服务 / USB 独占 / 回放增益 / 静音跳曲**：全不动。数据从哪来它们不关心
- **曲库快照**：不动（`.scratch/library-cache/` 工单 02 的成果照用）。只新增「已下载播放列表的本地清单」一节

## 测试决策

**缝**（越少越好）：

1. **`MediaDownloads` 接口** —— `FakeMediaDownloads` 测调度器与 ViewModel
2. **`DownloadLedgerStore` 接口** —— 内存 Fake + 文件实现各自测
3. **`DownloadScheduler` 的纯逻辑** —— 重试次数、跳过策略、状态聚合

**测什么**：

- **纯函数**：名单的序列化/反序列化；迁移的反查逻辑（URL → trackId）；占用格式化（复用现有 `formatBytes`）
- **`DownloadScheduler`**：串行（第二个任务排队）；失败重试 3 次后跳过；取消后已完成的保留；进度聚合正确
- **`JellyfinViewModel`**：退出登录**不**调任何下载清理；换账号后名单按账号过滤；离线时只有已下载的能放；迁移只跑一次
- **改名钉死**（沿用现有测试）：改名不改下载名单、不动字节
- **不测**：`SimpleCache` / `CacheWriter` 自己的行为、真实网络

**注意**：涉及轮询/长协程用有界 `advanceTimeBy`，别用 `advanceUntilIdle`（`QQDownloadViewModelTest` 的教训）。

## 明确不做

- **不做自动缓存**（这次就是要拆掉它）
- **不自动续传**（App 被杀后不自动恢复，用户手动再点）
- **不设总量上限**（空间不足交给系统报警）
- **不做「一键清空全部下载」**（逐个专辑删是有意的摩擦）
- **不做陈旧检测**（服务器上歌被换过不重下）
- **不做「已下载专辑有新增」提示**（要补就删了重下）
- **不自动补齐新增曲目**
- **不做转码下载**
- **不改 Jellyfin 数据层**
- **不做缓存预热**

## 风险与待实测

1. **`CacheWriter` 在只读 factory 上的行为**：`createDataSourceForDownloading()` 在 `cacheIsReadOnly = true` 时会不会也变只读？**bytecode 里 `createDataSourceForDownloading()` 和 `createDataSource()` 走同一个 `createDataSourceInternal`，读的是同一个 `cacheIsReadOnly` 字段。** 如果确实是共享的，就需要给下载单独建一个非只读的 factory。**动手第一步就要验证这一点**，别等下载跑完什么都没存。
2. **迁移的 URL 反查**：`cacheKey = streamUrl(track)`，需要从 URL 里解析 trackId。现有 `JellyfinUrlBuilder` 是「构造」方向，没有反解。**要么写个小心解析器，要么迁移时遍历当前曲目表逐个算 key 去比对**（后者更稳，曲库几百首，O(n) 无压力）。**采用后者。**
3. **`?static=true` 一定带 `Content-Length`**：`.scratch/library-cache/issues/01` 里这条还没真机验证过。**如果拿不到，去重与「完整」判定都会失效**，整条链路要换判据。装机后第一件事就核这个。
