# Pulsar 架构重构总索引

> 状态：**大部分已落地**。本目录下的文档是 2026-11-08 的架构评审执行规格，不是 ADR；行号已随票腐化，开工前按符号名 `grep` 确认，并以 GitHub Issue + 代码现状为准。

---

## 1. 七份文档

| # | 候选 | 文档 | 强度 | 主要触碰 |
|---|---|---|---|---|
| 1 | 播放会话收成 `PlaybackSession` | [`playback-session-refactor.md`](playback-session-refactor.md) | Strong | `MainActivity.kt` 播放段、`NowPlayingScreen.kt`、`PlaybackService.kt` |
| 2 | 离线策略收成 `OfflinePolicy` | [`offline-policy-refactor.md`](offline-policy-refactor.md) | Strong | `OfflineVisibleLibrary.kt`、`DiscoverSections.kt`、`CachedAlbums.kt`、`MainActivity.kt` 离线段、`JellyfinViewModel.kt` |
| 3 | 曲库加载与状态收成 `LibraryState` | [`library-state-refactor.md`](library-state-refactor.md) | Strong | `JellyfinUiState.kt`、`JellyfinViewModel.kt`、全部页面 Composable |
| 4 | 下载真相收成 `DownloadLibrary` | [`download-library-refactor.md`](download-library-refactor.md) | Strong | `DownloadActionButton.kt`、`DownloadScheduler.kt`、`MediaDownloads.kt`、`DownloadLedger.kt`、`PulsarApplication.kt` |
| 5 | 合并搜索视图收成纯 module | [`unified-search-refactor.md`](unified-search-refactor.md) | Worth exploring | `MainActivity.kt` 搜索段、`QQDownloadViewModel.kt` 搜索段、`JellyfinViewModel.kt` 搜索段 |
| 6 | 会话凭据收成 `SessionScope` | [`session-scope-refactor.md`](session-scope-refactor.md) | Worth exploring | `JellyfinApi.kt`、`JellyfinDataSource.kt`、`SessionStore.kt`、`PlaybackReporter.kt`、`PulsarApplication.kt` |
| 7 | 页面骨架收成 `LibraryScreenScaffold` | [`library-screen-scaffold-refactor.md`](library-screen-scaffold-refactor.md) | Speculative | `MainActivity.kt` 列表/详情段 |

## 2. 顺序

### 按你的要求（编号顺序）

```
1 → 2 → 3 → 4 → 5 → 6 → 7
```

### 按依赖推荐

```
1 → 4 → 2 → 3 → 5 → 6 → 7
```

为什么把 4 提前：`DownloadLibrary` 修的是「同一批字节五种说法」的正确性问题（跨账号按钮说已下载、离线却看不见；删除兜底可能删到共享字节）。它越早做，后面 `OfflinePolicy`（2）和 `LibraryState`（3）拿到的「已下载集合」越可靠。

### 依赖关系（软依赖，不阻塞）

| 候选 | 依赖 | 说明 |
|---|---|---|
| 2 OfflinePolicy | 4 DownloadLibrary（可选） | `downloadedTrackIds` 先由 ViewModel 传入；4 落地后改成 DownloadLibrary 提供 |
| 3 LibraryState | 2 / 4（可选） | `LibraryState` 里的 `DownloadState` slice 最好在 4 之后定型 |
| 4 DownloadLibrary | 6 SessionScope（可选） | 账号作用域先接 `AccountScope` 值；6 落地后换成 `SessionScope` |
| 5 UnifiedSearch | 2（可选） | 离线搜索过滤由 OfflinePolicy 统一；本方案只消费结果 |
| 7 Scaffold | 2（可选） | 离线提示/空态类型从 OfflinePolicy 取；在那之前先用字符串 |

## 3. 并行冲突（开多个窗口前必看）

**不要同时改同一批文件。** 下面这些文件会被多个候选触碰：

| 文件 | 被哪些候选碰 |
|---|---|
| `MainActivity.kt` | 1、2、3、5、7 |
| `JellyfinViewModel.kt` | 2、3、4、5、6 |
| `JellyfinUiState.kt` | 2、3、4、5 |
| `PulsarApplication.kt` | 4、6 |
| `QQDownloadViewModel.kt` | 5 |
| `PlaybackService.kt` | 1、6 |

**推荐并行方式**：
- 1 和 4 可以并行（文件几乎不重叠；1 碰播放，4 碰下载）。
- 1 和 7 不要并行（都改 MainActivity 的 UI 段）。
- 2 和 3 不要并行（都改 JellyfinViewModel / UiState）。
- 3 和 4 不要并行（都改 UiState 的下载字段）。
- 5 和 3 不要并行（都改 JellyfinViewModel 的搜索段）。
- 6 和 4 可以并行，但 `AccountScope` 接口先定好，别各写各的。

## 4. 每张票怎么开工（2026-11-09 起）

**现在的执行单位是 GitHub Issue，不是文档里的 Step。** 31 张票的正文自带该 Step 的规格摘录，
所以 agent 只需要读票 + 对应文档那一节，不用整份文档。

1. `gh issue view <n>` 拿到票的正文（含所属文档、Step、验收）。
2. 先做票里的 Step 0（基线 + 站点清单），确认没有和正在跑的票改同一批文件。
3. RED → GREEN → 重构；跑票里的验证命令。
4. 完成定义（用户 2026-11-09 拍板）：
   - 代码写完 + 全量单测绿 + `assembleDebug` 通过 = **这张票算完成**；
   - 在 issue 下留一条「待真机确认」评论，列出真机要点的项；
   - **不关票**，等用户真机验完自己关（2026-11-18 用户确认整体验收，全部票已关闭）。
5. 本地 commit，**不 push**（除非用户明确要求）。
6. 一张票做完再做下一张；同一批文件不要并行改（见 §3）。

> 文档末尾的「新窗口开工提示词」（§10）是 2026-11-08 的旧流程产物，保留作历史。
> 开工以 issue 为准；两者冲突时以 issue + 代码现状为准。

## 5. 当前状态（2026-11-18）

- 工单：`netwsqz/pulsar` #1–#31 + 索引 #32 + 文档维护 #33。**31 张票全部代码完成**（提交对照见 §5.2）。
  **2026-11-18 用户确认整体验收，全部工单（#1–#33）已关闭**；最后门禁为全量单测 985 项 0 失败、
  `assembleDebug` 通过（HEAD `207f98d`）。
- **#34（2026-11-18 新开，同日完成）**：QQ 下载状态删迁移期兼容层。代码写完，待真机确认。
- 生产代码：**7 个候选（1–7）全部落地**，另加遗留 #28–#31、#34。
- 文档：7 份执行规格 + 本索引。规格正文写于 2026-11-08，**行号已腐化**；
  开工前按符号名 `grep` 确认，不要直接信行号。每个已完成 Step 顶部有 `> 已完成 …` 注记
  （写清与原文的差异），原文不改写、保留决策历史。

| 候选 | 票 | 状态 |
|---|---|---|
| 1 播放会话 | #1 #2 #3 | ✅ 代码完成（2026-11-08） |
| 4 下载真相 | #4 #5 #6 #7 | ✅ 代码完成（2026-11-08 → 11-10） |
| 2 离线策略 | #8 #9 #10 | ✅ 代码完成（2026-11-11） |
| 3 曲库状态 | #11–#16 | ✅ 代码完成（2026-11-11 → 11-13） |
| 5 合并搜索 | #17 #18 #19 | ✅ 代码完成（2026-11-14 → 11-15） |
| 6 会话凭据 | #20 #21 #22 #23 | ✅ 代码完成（2026-11-15） |
| 7 页面骨架 | #24 #25 #26 #27 | ✅ 代码完成（2026-11-15 → 11-17） |
| 遗留（候选 1 衍生） | #28 队列单一真相 | ✅ 代码完成（2026-11-17） |
| 遗留（通用） | #29 首曲算不出地址不整条放弃 / #30 多选全选与可见列表一致 | ✅ 代码完成（2026-11-18） |
| 遗留（QQ） | #31 QQ 下载状态拆分 | ✅ 代码完成（2026-11-18） |
| 文档维护 | #33 | ✅ 2026-11-18：四份回退表补齐、本表与提交对齐 |
| 遗留（QQ 收尾） | #34 删除 QQ 下载迁移期兼容层 | ✅ 代码完成（2026-11-18） |

- 已知遗留：**无**。曲库侧的兼容层由 #16 删除，QQ 侧的由 #34 删除；
  `JellyfinUiState` 与 `QQDownloadUiState` 现在都只有 slice 字段。
- 架构评审报告：`C:\Users\netws\AppData\Local\Temp\architecture-review-20261107-235221.html`（临时目录，可能被系统清理；关键结论已写进这 7 份文档）。

### 5.1 文档步骤 ↔ 工单对照

工单是按文档的 Step 切的，但**文档里没写哪一步对应哪张票**。对照表放这里，省得每次翻 issue：

| 文档 | Step → 票 |
|---|---|
| `playback-session-refactor.md` | Step 0–1 无票（前置）；Step 2 → #1；Step 3 → #2；Step 4 → #3；Step 4 遗留 → #28 |
| `download-library-refactor.md` | Step 0–1 → #4；Step 2 → #5；Step 3–4 → #6；Step 5 → #7 |
| `offline-policy-refactor.md` | Step 0–2 → #8；Step 3 → #9；Step 4–5 → #10；Step 1 遗留 → #30 |
| `library-state-refactor.md` | Step 0–1 → #11；Step 2 → #12；Step 3 → #13；Step 4（列表页）→ #14；Step 4（详情/设置页）→ #15；Step 5–6 → #16；独立 → #31 |
| `qq-download-state-refactor.md` | #31（独立于曲库那条线的 QQ 下载状态拆分）；删兼容层 → #34 |
| `unified-search-refactor.md` | Step 0–1 → #17；Step 2–3 → #18；Step 4 → #19 |
| `session-scope-refactor.md` | Step 0–1 → #20；Step 2 → #21；Step 3 → #22；Step 4–5 → #23 |
| `library-screen-scaffold-refactor.md` | Step 0–1 → #24；Step 2 → #25；Step 3 → #26；Step 4–5 → #27 |

> 注意：文档的 Step 编号是**执行顺序**，工单编号是**拆票顺序**，两者只在同一份文档内一致。

### 5.2 工单 → 提交对照

查「某张票改了哪些文件」时直接 `git show <hash>`，不用再翻 issue 评论：

| 票 | 提交 | 票 | 提交 |
|---|---|---|---|
| #1 | `14b21b6` | #17 | `07e6c7e` |
| #2 | `9778547` | #18 | `b665427` |
| #3 | `ce4cffb` | #19 | `15c3c48` |
| #4 | `09167bd` | #20 | `d6c9f14` |
| #5 | `de65cf0` | #21 | `eeb00d2` |
| #6 | `9870774` | #22 | `1970287` |
| #7 | `429b1e4` | #23 | `124921d` |
| #8 | `7f16488` | #24 | `a3a1920` |
| #9 | `93dc976` | #25 | `30f57c9` |
| #10 | `557f6db` | #26 | `7668fb0` |
| #11 | `e36d958` | #27 | `4d1df8a` |
| #12 | `aff7b09` | #28 | `ed0fe4f` |
| #13 | `dfc4ed3` | #29 | `e558f98` + `bb21f26` |
| #14 | `f7d0f88` | #30 | `edde51c` + `2d0b45c` |
| #15 | `4317b0b` | #31 | `eb8b86d` |
| #16 | `bc5a844` | #33 | `a97aef6` + 2026-11-18 文档补齐提交 |
| #34 | `26c5c18` | — | — |

## 6. 共同的工程约定

- **TDD**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。
- **手写 Fake**：不用 Mockito / MockK。
- **不改无关代码**：每张票只做票里写的事。
- **安全**：token 不写日志、不写 URL、不写 UI；不硬编码密钥。
- **不删用户数据**：DataStore、下载字节、回收站、配置。
- **命令失败先看报错**，不硬猜；工具反复报错就停下来问。
- **UI 改动真机肉眼验证**，不用 adb 截图代替。
- **每张票完成后，在对应 Step 顶部加一条 `> 已完成 YYYY-MM-DD` 注记**，写清与原文的差异
  （`playback-session-refactor.md` 的 Step 0 / Step 1 是范例）。原文不改写——保留决策历史。
- **每个 Step 必须写「回退」**：做完发现不对，怎么退回去。2026-11-18 已补齐：`playback-session-refactor.md`
  每步自带「回退」行，`download-library-refactor.md` / `offline-policy-refactor.md` 在文末 §11，
  其余四份在 §6.1（或 Step 末尾）；新写文档照此办理。（`qq-download-state-refactor.md` 是设计文档、
  没有 Step 结构，不在此列。）

### 6.1 JVM 单测的硬限制（踩过）

Android 单元测试跑的是**桩化 android.jar**，不是真机运行时。以下在 JVM 测试里**测不了**：

- `Uri.parse()` 返回 `null`；
- `Bundle.EMPTY` 是 `null`，`Bundle` 的 extras 读写不可用；
- Media3 因此不会建 `localConfiguration`，URI / 封面 tag / extras 全都测不到。

含义：**涉及 URI、MediaItem extras、Bundle 传参的逻辑不要写 JVM 断言**，它们靠真机验收清单覆盖。
真的需要测，就把纯计算部分（字符串拼接、映射表）从 Android 对象上摘下来单独测。


## 7. 命令速查

```bash
# 每次新终端
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

# 全量单测
"$GRADLE" --no-daemon :app:testDebugUnitTest

# 单类
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.playback.PlaybackSessionTest'

# 构建
"$GRADLE" --no-daemon :app:assembleDebug

# 安装（无线调试端口每次可能变，先问用户）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" connect <ip>:<port>
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

## 8. 评审时用到的领域词

- **曲库**：PC 上的 `E:\file\Te_Music`，Jellyfin 指向它。
- **下载**：用户主动存到手机上的完整副本；不是自动缓存（ADR-0007）。
- **曲库快照**：手机上的「有哪些歌」清单，只在线下兜底（ADR-0008）。
- **离线可见性**：离线时只显示已下载；过滤在界面层派生，原始 state 不动（ADR-0008）。
- **合并搜索**：一个输入框同时搜曲库和 QQ 音乐；QQ 永远排在曲库之后（ADR-0006）。
- **QQ 音乐导入**：从 QQ 音乐抓歌进电脑曲库，不是手机下载（ADR-0006/0007）。
- **会话**：Jellyfin 的 serverUrl + userId + token；token 只存 DataStore，不进 URL/日志/UI。

## 9. 相关 ADR

- `0001` 文件管理走 PC 伴生服务
- `0002` 删除 = 移到回收站
- `0003` 缓存是副本（部分被 0007 取代）
- `0004` UI 参考 Auxio，只取规格不取代码
- `0005` 主题色板由封面鲜艳色推导
- `0006` 搜索合并，QQ 下载降为设置二级页
- `0007` 下载取代自动缓存；字节共享、可见性按账号
- `0008` 离线只显示已下载；过滤在界面层派生
- `0009` 删除流派页
