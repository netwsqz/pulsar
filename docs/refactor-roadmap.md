# Pulsar 架构重构总索引

> 状态：**全部待研究**。本目录下的文档是架构评审候选的执行规格，不是 ADR，也还没有改生产代码。
> 每份文档都自包含，可以单独开一个新窗口，把文档末尾的「新窗口开工提示词」贴进去。

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

## 4. 每个窗口怎么用

1. 在 `E:\nsmic` 开一个新的 Claude Code 窗口。
2. 把对应文档末尾的「新窗口开工提示词」贴进去。
3. 让 agent 先做 Step 0（基线 + 清单），确认没有和别的窗口改同一批文件。
4. 每个 Step 完成后：
   - 跑文档里的验证命令；
   - 真机按清单点一遍（UI 改动必须肉眼验证）；
   - 记录结果到文档或 GitHub Issue。
5. 一个候选做完再做下一个；不要在一个窗口里同时开两个候选。

## 5. 当前状态

- 生产代码：**未改动**。
- 文档：7 份执行规格 + 本索引。
- 架构评审报告：`C:\Users\netws\AppData\Local\Temp\architecture-review-20261107-235221.html`（临时目录，可能被系统清理；关键结论已写进这 7 份文档）。
- 工单：尚未创建 GitHub Issue。按 `docs/agents/issue-tracker.md` 的约定，开工时用 `gh` 把每个 Step 拆成 issue，或先按文档执行。

## 6. 共同的工程约定

- **TDD**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。
- **手写 Fake**：不用 Mockito / MockK。
- **不改无关代码**：每个 Step 只做文档里写的事。
- **安全**：token 不写日志、不写 URL、不写 UI；不硬编码密钥。
- **不删用户数据**：DataStore、下载字节、回收站、配置。
- **命令失败先看报错**，不硬猜；工具反复报错就停下来问。
- **UI 改动真机肉眼验证**，不用 adb 截图代替。

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
