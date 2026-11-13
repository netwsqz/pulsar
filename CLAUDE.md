# CLAUDE.md

Pulsar：单模块 Android Kotlin 的 Jellyfin 音乐播放器（Compose + M3、ViewModel + StateFlow、Ktor、Media3/MediaSessionService、DataStore、Coil；minSdk 26 / Java 17 / 仅 arm64-v8a，含 C++17 NDK 层）。代码注释与文档一律中文，沟通默认中文。

**TDD 强约定**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。
领域词汇见 `CONTEXT.md`（写代码、测试名、工单都用那里的词）；架构决策见 `docs/adr/`。

## 构建与测试（Windows）

没有 Gradle wrapper，用系统 Gradle 8.10.2；每次新开终端先设 SDK 环境变量，否则报 "SDK location not found"。

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

"$GRADLE" --no-daemon :app:testDebugUnitTest        # 全部单测（JUnit4，app/src/test）
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.data.jellyfin.JellyfinUrlBuilderTest'
"$GRADLE" --no-daemon :app:assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk（含 NDK 层）

# 无线调试（端口每次断开都变，先问用户）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" connect <ip>:<port>
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

lint 在 `app/build.gradle.kts` 里整体禁用（release 构建会崩），别去调它。

## 代码地图

单 `app` 模块，按层分包：

- `data/`（jellyfin / qqdownload / libraryfiles）：网络与 PC 服务，统一返回 `Result<T>`；`toAlbum()` 的 imageTag **只取 `Primary`，不回退 Backdrop**。
- `state/`（三个 slice）、`library/`（`LibraryCache`）、`offline/`（纯 module）、`download/`（`DownloadLibrary`）、`playback/`（含 `usb/`）、`ui/`（theme / components）、`domain/model/`、`server/`（PC 伴生 Python 服务）。
- 页面级 Composable 仍集中在 `MainActivity.kt`（约 5000 行）；`NowPlayingScreen.kt`、`QQDownloadScreen.kt` 独立成文件。

## 跨层约定

### 鉴权与会话

- **Token 只进 `Authorization` header**（`JellyfinUrlBuilder` 生成 MediaBrowser 格式），**绝不进 URL、日志或 UI**。该 builder 还负责 baseUrl 校验（HTTPS 或私网 HTTP）与路径段编码，返回 `JellyfinRequest(url, headers)`。
- `PulsarApplication.activeAuthorization`（`@Volatile`，仅内存）是播放流与封面图共用的鉴权来源；`AuthHttpDataSourceFactory` 每次建数据源现读，重新登录不用重建播放器。
- `SessionRepository`（DataStore）只存 token，**不存密码**；网络错误统一映射 `JellyfinError`；`execute {}` 把异常转 `Result.failure`，**`CancellationException` 一律重抛**。
- **登录/会话过期必须清空曲库缓存与播放队列**（`JellyfinUiState.cleared()`），代次挡住过期协程。

### 界面状态与曲库加载

- `JellyfinUiState` 只有三个字段：`session` / `library` / `downloads`，对外仍是一条 `StateFlow`（ADR-0010）。屏幕直接读写 slice（`state.library.xxx`）；`JellyfinUiStateTest` 钉死形状，**别往组合根加字段**。
- 详情页的「当前专辑 / 播放列表」**不在 UI 状态里**：导航真相是 `LibrarySection.AlbumDetail` / `PlaylistDetail`，兜底用 `LibraryCache.currentAlbum` / `currentPlaylist`。别把 `selectedAlbum` / `selectedPlaylist` 加回 `LibraryState`。
- 顶部页签与详情来源用 `rememberSaveable(stateSaver = LibrarySectionSaver)`（`LibrarySection.kt`），别改回 `remember`。
- 清空语义收在 slice 的 `cleared()`，约定「字段默认值 = 已清」。
- **`LibraryCache`**：每节一份 job + 代次，互不取消。三态只在这里实现：`null` = 没拉到/失败（会重试）、空列表 = 服务端确实没有、**失败绝不写空列表**。界面只调 `viewModel.onSectionEntered(section)`；会话就绪/离线翻转/切歌补跑由 module 触发，**别往 UI 的 LaunchedEffect 加 sessionReady / isOffline / historyEpoch 当 key**。
- `isLoading` / `errorMessage` 仍是登录与曲库**共用**通道（`isLoading` 按在途节计数）。
- 发现页三节历史来自服务端（`SortBy=DatePlayed/PlayCount/DateCreated`）；单节失败保持 `null` 并记入 `discoverFailedSections`，三节全失败才给错误+重试。
- 搜索防抖 300ms；空串立即清结果、不发请求。

### 播放

- **`PlaybackSession` 是播放 UI 的唯一门面**：界面只读 `PlaybackUiState`、只发 `PlaybackIntent`，不直接持有 `MediaController`。`PlaybackRemote` 是控制器接缝（生产 `MediaControllerPlaybackRemote`，测试 `FakePlaybackRemote`）。
- 播放模式真相在 `PlaybackUiState.mode`，由 `PlaybackModeStore` 落盘。
- `PlaybackReporter` 负责 `/Sessions/Playing` + `/Stopped`——**发现页的「最近/最常播放」完全依赖它**。铁律：异步不拖慢播放；失败静默且**不触发 `goOffline()`**；同一首只报一次开始、没报开始的不报停止（`Mutex` 串行）。
- `LoudnessGainAudioSink`（回放增益，**只衰减不提升**）是拷贝型包装 sink，四个坑：①不能改 Media3 交来的 buffer；②`setAudioProcessors` 在 float 输出下会被静默丢掉；③交给 `DefaultAudioSink` 的缓冲必须声明**小端**；④每帧交**同一个**缓冲对象。
- USB 独占在 `playback/usb/`，调研见 `docs/usb-exclusive-research.md`。等时泵跑专用线程；格式协商失败回退系统输出、不抛异常；暂停用**静音门**而非 stopStream（避免 EBUSY）。
- USB 独占下系统音量无效，音量真源是 `UsbExclusivePreferences.volumePercent`；**前台音量键那条路才干活**（`MainActivity.dispatchKeyEvent` → `ADJUST_VOLUME`，传 ±1 档），MIUI 上 `VolumeProvider` 失效。接管条件是「USB 泵正在输出」不是独占开关；**音量算术只在服务一处**；DOWN 与 UP 同进同退（`lastDownConsumed`）。见 `docs/usb-volume-key-interception.md`。

### 离线与下载

- **曲库快照** `filesDir/library-snapshot.json`：只在线下兜底，能连服务器时一律以服务器为准；覆盖发现/全部音乐/专辑 + 离线搜索，播放列表不在其中。
- **下载**是用户主动存的原文件副本（`SimpleCache`，`filesDir/media_cache`）。**播放链路纯只读、绝不写盘**（`setCacheWriteDataSinkFactory(null)`），写盘只有 `CacheWriter` + `createDataSourceForDownloading()`；`NoOpCacheEvictor`（无自动淘汰、无上限、无一键清空）；只有完整副本才算数；`PulsarApplication.mediaCacheStore` 是进程级单例，服务销毁时不能释放。
- **`DownloadLibrary` 是「已下载」的唯一真相**：`已下载 = 字节完整 ∩ 当前账号名单`。界面只读 `DownloadSnapshot`、只发 `enqueue` / `cancel` / `delete`；名单读改写只走 `DownloadLedgerStore.update`。
- **`offline/OfflinePolicy.kt` 是纯 module**：输入 `OfflineInput`，输出 `OfflineView`。界面只读 `OfflineView`，不要自己拼 `isOffline && cachedTrackIds`；离线文案只在 `OfflineNotice` / `OfflineEmpty` / `OfflineSearchNotice` 的 `message()` 里定义。
- 离线时各屏**只显示已下载**（ADR-0008），过滤是界面层派生，原始列表保持全量、**别改 state**。专辑有一首已下载就显示；`downloadedTrackIds == null` = 未知不筛，空集才是「一首都没下载」。离线时播放队列不动。
- `isOffline` 是全局态，**任意**请求的 `Network` 错误都会 `goOffline()`。退出登录/过期**不清下载**，只清曲库快照。
- `SimpleCache` 三个坑：残条目 `isCached(key, 0, -1)` 抛 `IllegalArgumentException`；构造失败延迟到读取时才报；清空/退出登录要 `keepKeys` 跳过正在播的 key。

### UI

- `ui/theme/Dimens.kt` 是设计 token 唯一出处，不写魔法 dp。玻璃面统一走 `GlassPanel`（全屏播放页两处圆钮是已知例外）。
- 主题色板由当前播放封面的**鲜艳色**推导（ADR-0005），手动预设色只兜底。**不要引入 `material-color-utilities` 系依赖**。
- **不引入导航库**：顶层顺序只由 `TopLevelSections` 决定（发现/歌曲/专辑/艺术家/播放列表）；流派页已删除（ADR-0009），别加回来。
- 浮层锚点：顶部只放导航/选择，底部只放瞬时反馈；Snackbar 与音量胶囊共用 `Modifier.bottomDockAnchor`，**不要写固定底距**。
- 多选：`multiSelectIds` 非空即多选态。

### QQ 音乐导入（PC 伴生服务）

`server/qqmusic_service.py`（FastAPI，8900，`start.bat` 手动启动）只调用不修改 `E:\music` 的现成函数，落盘 `E:\file\Te_Music`；QQ cookie 只在 PC 侧，**App 不接触**。Android 侧 `data/qqdownload/` + `QQDownloadViewModel` 与 Jellyfin 会话完全独立，搜索 300ms 防抖、任务轮询 2s。入口是「设置 → QQ 音乐导入」，不占顶层页签。

## 测试

- JUnit4 + `kotlinx-coroutines-test` + Ktor `MockEngine`；测试名用反引号描述行为。**手写 Fake 优先**（不用 Mockito/MockK），Fake 定义在测试文件内。
- **虚拟时间会活锁**：`viewModelScope` 的无限 `delay` 循环配 `runTest` 会卡死 gradle daemon。周期循环必须有上限；测轮询用有界 `advanceTimeBy`，**不要 `advanceUntilIdle`**。
- 纯函数优先拆出来单测（`DiscoverPicks`、`LibraryGroups`、`PlaybackLogic`、`OfflinePolicy` 等）。
- **UI 没有 Compose 测试设施**：布局改动靠真机肉眼验证，不要为样式写 UI 测试。

## 红线与协作

- **改动范围尽量小**，不做无关重构；不删用户数据（DataStore、配置、历史）；token/密码不写日志、URL、UI。
- **git push 已获长期授权**：提交后直接推 `origin master`。**部署、发布、破坏性迁移仍要先问**。
- 命令失败先看报错，不硬猜；工具反复报错就停下来问。
- 根目录 `qq-music-api-v2/`、`node_modules/`、`qrcode*.png`、`qrresp*.json`、`{var`、`Poppins,Righteous/`、`*.log` 是历史误产物（已 gitignore），不是项目代码。
- `docs/archive/` 是过时归档；进行中的重构见 `docs/refactor-roadmap.md`。
- 工单走 GitHub Issues（`netwsqz/pulsar`，`gh` CLI，`docs/agents/` 下有约定）；`.scratch/` 只作历史。
- 已接受 ADR 0001–0010；最常约束写代码的：0004 只取 Auxio 规格、0007 下载取代自动缓存、0008 离线只显示已下载、0009 顶层 5 项、0010 按所有权分 slice。
