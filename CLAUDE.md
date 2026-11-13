# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

**Pulsar**：单模块 Android Kotlin 应用，面向普通手机的 Jellyfin 音乐播放器 MVP。
技术栈：Jetpack Compose + Material 3、ViewModel + StateFlow、Ktor + kotlinx.serialization、Media3/ExoPlayer + MediaSessionService、DataStore、Coil。minSdk 26 / targetSdk 35 / Java 17；带 C++17 NDK 层（CMake 3.22.1，ABI 仅 `arm64-v8a`）。

代码注释与文档一律中文，与用户沟通默认中文。**TDD 强约定**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。
领域词汇（曲库 / 下载 / 快照 / 离线视图 / 播放会话…）见 `CONTEXT.md`，写代码、测试名、工单时用那里的词；架构决策见 `docs/adr/`。

## 构建与测试命令（Windows）

**没有 Gradle wrapper**，用系统 Gradle 8.10.2；每次新开终端先设 SDK 环境变量，否则报 "SDK location not found"。

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

# 全部单元测试（JUnit4，app/src/test）
"$GRADLE" --no-daemon :app:testDebugUnitTest

# 只跑某个测试类
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.data.jellyfin.JellyfinUrlBuilderTest'

# debug APK（产物：app/build/outputs/apk/debug/app-debug.apk，同时编译 NDK 层）
"$GRADLE" --no-daemon :app:assembleDebug

# 无线调试安装
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" connect <ip>:<port>
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

lint 任务在 `app/build.gradle.kts` 里被整体禁用（release 构建时 lint 崩溃），别去调它。

## 代码地图

单 `app` 模块，按层分包：

- `data/jellyfin/`、`data/qqdownload/`、`data/libraryfiles/`：网络与 PC 服务访问，统一返回 `Result<T>`。DTO 转换里 `toAlbum()` 的 imageTag **只取 `Primary`，不要回退到 Backdrop**。
- `state/`：`JellyfinUiState` 的三个 slice（见「界面状态与曲库加载」）。`library/`：曲库加载 `LibraryCache`。
- `offline/`：离线策略纯 module。`download/`：下载真相 `DownloadLibrary`。
- `playback/`（含 `usb/`）：播放会话与 USB 驱动。
- `ui/theme/`、`ui/components/`：设计 token、液态玻璃、通用组件。`domain/model/`：领域模型。
- **页面级 Composable 仍集中在 `MainActivity.kt`（约 5000 行）**；`NowPlayingScreen.kt`（全屏播放页）与 `QQDownloadScreen.kt` 独立成文件。
- `server/`：PC 伴生服务（Python，见下文 QQ 音乐下载）。

## 跨层约定

### 鉴权与会话

- **Token 只进 `Authorization` header**（MediaBrowser 格式，由 `JellyfinUrlBuilder` 生成），**绝不进 URL、日志或 UI**。`JellyfinUrlBuilder` 还负责 baseUrl 校验（HTTPS 或私网 HTTP）、路径段编码，返回 `JellyfinRequest(url, headers)`。
- `PulsarApplication.activeAuthorization`（`@Volatile`，仅内存）是**播放流与封面图共用的鉴权来源**；登录/恢复写入，登出/过期清空。`AuthHttpDataSourceFactory` 每次建数据源时现读，重新登录不用重建播放器。
- `SessionRepository`（DataStore）只持久化 token，**不存密码**；`InstallationIdStore` 提供 `DeviceId`。
- 网络错误统一映射 `JellyfinError`（`InvalidCredentials` / `Unauthorized` / `Http` / `InvalidResponse` / `Network`）；`execute {}` 把异常转 `Result.failure`，**`CancellationException` 一律重抛**。
- **登录/会话过期必须清空所有曲库缓存与播放队列**（`JellyfinUiState.cleared()`），防止跨账号残留；代次（`loginGeneration` 等）挡住过期协程。

### 界面状态与曲库加载

- `JellyfinUiState` 是**组合根**，**只有三个字段**：`session`（登录表单、恢复、错误通道）、`library`（曲库/发现/详情/搜索/多选/文件操作/离线）、`downloads`（下载派生的 UI 字段）。对外仍是一条 `StateFlow`（ADR-0010）。**屏幕直接读写 slice**（`state.library.xxx`）；平铺字段与迁移期兼容层已在 #16 删除，`JellyfinUiStateTest` 钉死形状，别再往组合根加字段。
- 详情页的「当前专辑 / 播放列表」**不在 UI 状态里**：导航真相是界面层的 `LibrarySection.AlbumDetail(album)` / `PlaylistDetail(playlist)`，幂等守卫与离线快照兜底用 `LibraryCache.currentAlbum` / `currentPlaylist`（session 切换时一并作废）。别把 `selectedAlbum` / `selectedPlaylist` 加回 `LibraryState`。
- 顶部页签与详情来源用 `rememberSaveable(stateSaver = LibrarySectionSaver)`（`LibrarySection.kt`）：转屏 / Activity 重建后当前页与返回路径不丢。别改回 `remember`。
- 登出/过期的清空语义收在 slice 的 `cleared()` 里，约定「字段默认值 = 已清」。`downloads.cleared()` 清的是 UI 字段；ADR-0007 的「下载不清」说的是字节与名单。
- **`LibraryCache`**：每节（专辑/发现随机/三节历史/专辑曲目/播放列表/播放列表曲目/搜索）一份 job + 代次，**互不取消**。三态语义只在这里实现：`null` = 没拉到/失败（会重试）、空列表 = 拉到了但服务端没数据、**失败绝不写空列表**。界面只调 `viewModel.onSectionEntered(section)`；会话就绪/离线翻转/切歌后的补跑由 module 触发，**不要再往 UI 的 LaunchedEffect 加 sessionReady / isOffline / historyEpoch 当 key**。
- `isLoading` / `errorMessage` 仍是登录与曲库**共用**的通道（`isLoading` 按在途节计数），别当成已经分节。
- 发现页三节历史来自服务端（`SortBy=DatePlayed/PlayCount/DateCreated`），**不来自本地存储**。单节失败保持 `null` 并记入 `discoverFailedSections`，其余照常；三节全失败才给错误+重试。
- 搜索防抖 300ms（`updateSearchQuery`），空串立即清结果不发请求。

### 播放

- **`PlaybackSession` 是播放 UI 的唯一门面**：界面只读 `PlaybackUiState`、只发 `PlaybackIntent`，不直接持有 `MediaController`。连接/监听、500ms 轮询、自定义命令解析、错误映射都在会话内部。
- `PlaybackRemote` 是控制器接缝（生产 `MediaControllerPlaybackRemote`，测试 `FakePlaybackRemote`）；`TrackMediaItemFactory` / `PlaybackGate` 让会话单测不依赖 ViewModel。
- 播放模式真相在 `PlaybackUiState.mode`，由 `PlaybackModeStore`（DataStore）落盘，转屏/重建后恢复。
- `PlaybackReporter` 负责 `POST /Sessions/Playing` + `/Stopped`——**发现页的「最近播放/最常播放」完全依赖它**。三条铁律：异步不拖慢播放；失败静默且**不触发 `goOffline()`**；同一首只报一次开始、没报开始的不报停止。上报串行（`Mutex`）。历史为空先查这里。
- `LoudnessGainAudioSink`（回放增益，只衰减不提升）是**拷贝型**包装 sink，四个坑别重蹈：①**不能改 Media3 交来的 buffer**（解码器输出只读）；②`setAudioProcessors` 在 float 输出下会被静默丢掉，别用；③交给 `DefaultAudioSink` 的缓冲必须声明**小端**；④**每帧交同一个缓冲对象**（它断言没吃完的 buffer 下次还是它），不要按当帧增益切换缓冲。
- USB 独占子系统在 `playback/usb/`（约 15 个 Kotlin 文件 + NDK 层），调研与架构见 `docs/usb-exclusive-research.md`。约定：等时泵跑专用线程（不在 Main/IO dispatcher）；格式协商失败回退系统输出、不抛异常；暂停用**静音门**而非 stopStream（避免重新 claim 造成 EBUSY）。
- USB 独占下系统音量无效，音量真源是 `UsbExclusivePreferences.volumePercent`。**前台音量键那条路才是真干活的**（`MainActivity.dispatchKeyEvent` → 自定义命令 `ADJUST_VOLUME`，传 ±1 档不传绝对值），MIUI 上标准 `VolumeProvider` 路失效。接管条件是「USB 泵正在输出」（`state == streaming`）**不是独占开关**；算术只在服务一处；DOWN 与 UP 必须同进同退（`lastDownConsumed`）。细节见 `docs/usb-volume-key-interception.md`。

### 离线与下载

- **曲库快照 `LibrarySnapshot`**：`filesDir/library-snapshot.json` 的「有哪些歌」清单，**只在线下兜底**——能连服务器时一律以服务器为准。覆盖发现/全部音乐/专辑 + 离线搜索；播放列表不在里面（下载过的另存）。
- **下载 `MediaDownloads`**：用户主动存的原文件副本（`SimpleCache`，`filesDir/media_cache`，**不是 cacheDir**）。**播放链路纯只读、绝不写盘**（`setCacheWriteDataSinkFactory(null)`），写盘只有 `CacheWriter` + `createDataSourceForDownloading()`。`NoOpCacheEvictor`：没有自动淘汰、总量上限、一键清空。只有完整副本才算数。`PulsarApplication.mediaCacheStore` 是**进程级单例**，服务销毁时不能释放。
- **`DownloadLibrary` 是「已下载」的唯一真相**：`已下载 = 字节完整 ∩ 当前账号名单`。界面只读 `DownloadSnapshot`、只发 `enqueue` / `cancel` / `delete`，**不要再自己查字节或算交集**；名单读改写只走 `DownloadLedgerStore.update`。
- **`offline/OfflinePolicy.kt` 是纯 module**（无状态、无 IO）：输入 `OfflineInput`，输出 `OfflineView`（可见曲库、是否在筛、提示、发现页组合、空态、搜索标识）。**界面只读 `OfflineView`**，不要自己拼 `isOffline && cachedTrackIds`；离线文案只在 `OfflineNotice` / `OfflineEmpty` / `OfflineSearchNotice` 的 `message()` 里定义。
- 离线时各屏**只显示已下载**（ADR-0008），过滤是**界面层派生**（原始列表保持全量，回在线自动恢复，**别改 state**）。专辑有一首已下载就显示；`downloadedTrackIds == null` = 未知，不筛；空集才是「一首都没下载」。离线时播放队列不动。
- `isOffline` 是全局离线态，**任意**网络请求的 `Network` 错误都会 `goOffline()`。
- 退出登录/会话过期**不清下载**；只有曲库快照会清。
- Media3 `SimpleCache` 三个坑：残条目 `isCached(-1)` 抛 `IllegalArgumentException`；构造失败延迟不崩溃；删资源要跳过正在播放的 key。

### UI

- `ui/theme/Dimens.kt` 是**设计 token 的唯一出处**（间距/触摸目标/圆角），不写魔法 dp。
- 玻璃面统一走 `GlassPanel`（`ui/components/LiquidGlass.kt`），不要直接调 `liquidGlass` 或各写模糊/透镜数字。全屏播放页两处圆钮是已知例外。
- 主题色板由当前播放封面的**鲜艳色**推导（`CoverPalette` + `ThemePalette`），手动预设色只兜底（ADR-0005）。**不要引入 `material-color-utilities` 系依赖**（material3 1.4.0 没有公开的种子色 API，第三方坐标要求 Gradle 9.8 / JVM 21）。
- **不引入导航库**：`LibrarySection` + `TopLevelSections`（`LibrarySection.kt`）定义顶层顺序：**发现 / 歌曲 / 专辑 / 艺术家 / 播放列表**。搜索、设置、详情页不在顶层。改顺序只改 `TopLevelSections`。流派页已删除（ADR-0009），别加回来。
- 浮层锚点：顶部只放导航/选择，底部只放瞬时反馈。Snackbar 与音量胶囊共用 `Modifier.bottomDockAnchor`；**不要写固定底距**（80/320dp 那类）。
- 多选：`multiSelectIds` 非空即多选态，`TrackRow` 点击变勾选，行尾「更多」隐藏。

### QQ 音乐下载（PC 伴生服务）

- PC 侧 `server/qqmusic_service.py`（FastAPI，端口 8900，`start.bat` 手动启动）包装 `E:\music` 的现成函数（**只调用不修改**），串行下载，落盘 `E:\file\Te_Music`（Jellyfin 实时监控，落盘即入库）。凭证（QQ cookie）只在 PC 侧，**App 不接触**。
- Android 侧 `data/qqdownload/` + `QQDownloadViewModel`，与 Jellyfin 会话**完全独立**；搜索 300ms 防抖，任务轮询 2s。服务地址从 Jellyfin 地址自动推导（同机 8900）。
- 入口是「设置 → QQ 音乐导入」（`LibrarySection.DownloadService`），不占顶层页签。

## 测试

- JUnit4 + `kotlinx-coroutines-test` + Ktor `MockEngine`；测试名用反引号描述行为。**手写 Fake 优先**（不用 Mockito/MockK），Fake 定义在测试文件内。
- **虚拟时间会活锁**：`viewModelScope` 里的无限 `delay` 循环配 `runTest` 会卡死 gradle daemon。周期循环必须有上限；测轮询用有界 `advanceTimeBy`，**不要 `advanceUntilIdle`**。
- 纯函数优先拆出来单测（`DiscoverPicks`、`LibraryGroups`、`PlaybackLogic`、`OfflinePolicy` 等）。
- **UI 没有 Compose 测试设施**：布局改动靠真机肉眼验证，不要为样式写 UI 测试。

## 红线与协作

- **改动范围尽量小**，不做与任务无关的重构。不删除用户数据（DataStore、配置、历史）；token/密码不写日志、URL、UI。
- **git push 已获长期授权**：提交后直接推 `origin master`（2026-11-11 用户要求）。**部署、发布、破坏性迁移仍要先问**。
- 命令失败先看报错，不硬猜；工具反复报错就停下来问。
- 根目录 `qq-music-api-v2/`、`node_modules/`、`qrcode*.png`、`qrresp*.json`、`{var`、`Poppins,Righteous/`、`*.log` 是历史实验/误产物，已 gitignore，**不是项目代码**。
- **`docs/archive/` 是过时归档**，只作历史参考；新的阶段性总结也放这里。正在进行的架构重构见 `docs/refactor-roadmap.md`。
- 工单走 GitHub Issues（`netwsqz/pulsar`，`gh` CLI，见 `docs/agents/issue-tracker.md`）；`.scratch/` 只作历史。triage 标签见 `docs/agents/triage-labels.md`，领域文档约定见 `docs/agents/domain.md`。
- 已接受 ADR 0001–0010（`docs/adr/`）。最常约束写代码的：**0004** 只取 Auxio 设计规格、不取代码；**0007** 下载取代自动缓存、播放链路纯只读；**0008** 离线只显示已下载；**0009** 顶层 5 项、无流派页；**0010** state 按所有权分 slice、组合后仍单一 StateFlow。
