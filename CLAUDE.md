# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

**Pulsar**：单模块 Android Kotlin 应用，面向普通手机的 Jellyfin 音乐播放器 MVP。
技术栈：Jetpack Compose + Material 3、ViewModel + StateFlow、Ktor + kotlinx.serialization（数据层）、Media3/ExoPlayer + MediaSessionService（播放）、DataStore（会话/安装 ID）、Coil（封面图）。minSdk 26 / targetSdk 35 / Java 17。构建含 C++17 NDK 层（CMake 3.22.1，ABI 仅 `arm64-v8a`）。

代码内注释为中文；与用户沟通默认用中文。**本项目是 TDD 强约定**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。

## 构建与测试命令（Windows）

**关键环境要求：** 项目**没有 Gradle wrapper**，用系统 Gradle 8.10.2；且必须先设置 Android SDK 环境变量，否则报 "SDK location not found"。构建同时编译 C++17 NDK 层（CMake 3.22.1，ABI 仅 `arm64-v8a`）。

```bash
# 每次新开终端，先设置环境变量
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

# 运行全部单元测试（JUnit4，在 app/src/test）
"$GRADLE" --no-daemon :app:testDebugUnitTest

# 只跑某个测试类（--tests 可过滤）
"$GRADLE" --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.data.jellyfin.JellyfinUrlBuilderTest'

# 构建 debug APK（产物：app/build/outputs/apk/debug/app-debug.apk）
"$GRADLE" --no-daemon :app:assembleDebug

# 无线调试安装（连接后执行）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" connect <ip>:<port>
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 能直接打开此项目（同步 Gradle 后运行 `app` debug 配置）。

## 架构总览

单 `app` 模块，按层组织，但没有强制分层包。**页面级 Composable 仍集中在 `MainActivity.kt`（约 4400 行）**，播放页与 QQ 下载页是独立文件；主题/通用组件/领域模型已分到各自目录。

### 数据层 `data/jellyfin/`

- **`JellyfinApi`**（接口）/ **`JellyfinDataSource`**（实现）：Ktor 客户端封装。所有网络调用返回 `Result<T>`，错误统一映射为 `JellyfinError` sealed class（`InvalidCredentials` / `Unauthorized` / `Http(statusCode)` / `InvalidResponse` / `Network`）。`execute {}` 包装器把异常转成 `Result.failure`，**`CancellationException` 一律重新抛出**。
- **`JellyfinApiFactory`**（接口）：允许测试时注入 Fake 实现，生产时通过 `PulsarApplication.jellyfinApiFactory()` 创建真实实例。
- **`JellyfinUrlBuilder`**：纯函数式 URL 构建，**把凭据排除在 URL 之外**是它的核心职责。校验 baseUrl 必须是 HTTPS 或私网 HTTP（`normalizeBaseUrl`）；ID/token 空值抛 `IllegalArgumentException`；路径段做百分号编码（`pathSegment`）。返回 `JellyfinRequest(url, headers)` 结构——token 只进 `Authorization` header，不进 URL。
- **`JellyfinDtos`**：kotlinx.serialization DTO（`@SerialName` 映射 Jellyfin 大写字段）+ `toTrack()` / `toAlbum()` 转换函数。注意：`toAlbum()` 的 imageTag 只取 `imageTags["Primary"]`（不要回退到 Backdrop）。

### 会话与鉴权（跨层安全约定）

- **Token 绝不出现在 URL、日志或 UI**。请求头用 MediaBrowser 格式：
  `MediaBrowser Client="Pulsar", Device="Android", DeviceId="$deviceId", Version="0.1.0", Token="$token"`
- `PulsarApplication.activeAuthorization`（`@Volatile`，仅内存）：登录/恢复登录时写入完整 Authorization 头，退出登录/会话过期时清空。它是**播放流**和**封面图**共用的鉴权来源。
- 播放流：`AuthHttpDataSourceFactory` 每次创建数据源时从 `activeAuthorization` 读取当前头，重新登录后无需重建播放器。
- `SessionRepository`（接口）/ `SessionStore`（DataStore 实现）：持久化登录会话，只存 token，**不存密码**。`AuthorizationSink`（接口）：内存鉴权头的读写门面，`PulsarApplication` 实现它。
- 安装 ID：`InstallationIdStore`（DataStore），作为 `DeviceId` 传给 Jellyfin。
- **会话切换/过期约定**：`login()` 发起前、`expireSession()` 触发时，必须同时清空所有库缓存（albums/playlists/search 等）和播放队列，防止跨账号数据残留。`JellyfinViewModel` 用 `loginGeneration`/`albumsGeneration` 整数代次防止过期协程修改新会话状态。

### 播放 `playback/` + `PlaybackService.kt`

- `PlaybackService`：Media3 `MediaSessionService`，用 `AuthHttpDataSourceFactory` 建 ExoPlayer。`onTaskRemoved` 时若无播放内容则停止服务。
- `PlaybackMode.kt`：三种模式（顺序循环/单曲循环/随机）与 Media3 设置的映射。
- `PlaybackLogic.kt`：`buildPlaybackOrder`（旋转队列使点击的曲目开头）、`formatPosition`（`m:ss`）。
- `SleepTimer.kt`：定时停止播放，倒计时到 0 时通过 `MediaController` 暂停。
- `SilenceSkipDetector.kt` / `SilenceSkippingAudioSink.kt` / `SilenceLevelMonitor.kt`：静音跳过子系统。`SilenceSkippingAudioSink` 包装 Media3 `ForwardingAudioSink`，检测连续静音帧并跳过。阈值和开关存在 `SilenceSkipPreferences`（DataStore）。
- `AudioLevelMeter.kt`：实时音量电平，供 UI 展示波形。
- `LoudnessGain.kt` / `LoudnessGainAudioSink.kt`：回放增益（ReplayGain）。增益取 Jellyfin 的 `NormalizationGain`（服务端按 -18 LUFS / ReplayGain 2.0 基准现算），经 `MediaItem` 元数据 **extras** 传到播放服务（本地 tag 到不了服务端），换歌时换算成线性系数写进 `LoudnessGainProvider`（@Volatile 单值邮箱，同 `SilenceLevelMonitor` 套路）。**只衰减不提升**，防止安静曲目被提升后削顶。开关存 `LoudnessPreferences`（DataStore，默认开）。
  `LoudnessGainAudioSink` 是**拷贝型**包装 sink（夹在电平表与输出链路之间，USB 独占与系统输出行为一致）。四个踩过的坑，别重蹈：
  **(1) 绝不能直接改 Media3 交给 sink 的 buffer**——那是解码器输出，只读，写它抛 `ReadOnlyBufferException`；
  **(2) 别用 `DefaultAudioSink.Builder.setAudioProcessors` 挂自定义处理器**——Media3 1.5.1 只在非 float 分支组装自定义链，float 输出下会被静默丢掉；
  **(3) 交给 `DefaultAudioSink` 的缓冲必须声明小端**——它断言 `order() == LITTLE_ENDIAN`，而 `allocateDirect()` 默认是大端；
  **(4) 每一帧都必须交同一个缓冲对象**——它断言"没吃完的 buffer 下次还是它"，所以**不要**按当帧增益去决定交哪个缓冲（"增益为 1 就走零拷贝"那类快路径，只要在它攥着缓冲时翻转就会换对象、播崩）。统一走同一个 scratch 即可，多一次 memcpy 而已。
  拷贝方案里上游永远只读、下游没吃完就把上游 position 退回去重投，源数据不变 ⇒ 每个样本恰好乘一次。
- `PlayerRebuildState.kt`：跟踪播放器重建状态（USB 模式切换时需要销毁/重建 ExoPlayer）。
- `PlaybackDiagnostics.kt`：诊断单例，采样率/设备状态等供 UI 展示。
- `PlaybackReporter.kt`：**把播放行为上报给 Jellyfin**（`POST /Sessions/Playing` + `/Stopped`），服务端的 `UserData` 才会记录 `PlayCount` / `LastPlayedDate`——发现页的「最近播放 / 最常播放」完全依赖它。三条铁律：①异步、绝不拖慢播放；②失败静默（未登录/断网只记日志，**不触发 `goOffline()`**）；③同一首歌只报一次开始、没报过开始的不报停止。上报串行（`Mutex`），保证「停上一首」排在「开始下一首」之前。**若发现历史是空的，先查这里有没有断。**
- `UsbAudioOutput.kt`：USB 音频设备插拔监听与路由（插 DAC 强制 `setPreferredAudioDevice`，拔掉回系统默认）。
- **USB 独占下的音量键**（`RemoteVolumePlayer.kt` + `ForegroundVolumeKeys.kt`，详见 `docs/usb-volume-key-interception.md`）：独占模式绕过 AudioFlinger，系统音量对输出无效，音量真源是 `UsbExclusivePreferences.volumePercent`。两条接管路径——
  - **标准路**：`RemoteVolumePlayer`（`ForwardingPlayer` 子类）让 Media3 自动注册 `VolumeProvider`，系统把音量键交给 App。给 AOSP 设备用；**MIUI 上整条失效**（ROM 保留了 `setPlaybackToRemote` 的 API 却删掉了音量键派发，App 侧绕不过去）。
  - **前台路（真正干活的那条）**：`MainActivity.dispatchKeyEvent` 吃掉 `KEYCODE_VOLUME_UP/DOWN` → 自定义命令 `ADJUST_VOLUME`（**传 ±1 档，不传绝对值**）→ `PlaybackService.applyUsbVolume`。只在 Pulsar 位于前台时有效，息屏/后台调不了。**接管条件是「USB 泵正在输出」（`state == streaming`），不是独占开关**——开关开着但没插设备时声音走系统 AudioTrack，接管会让音量键彻底失灵。
  - **两个别改坏的约定**：①算术只在服务一处（UI 侧只有 500ms 轮询快照，自己算会丢档）；②DOWN 与 UP 必须同进同退（`lastDownConsumed`），只吃一个会让系统行为变得莫名其妙。

#### USB 专属驱动子系统 `playback/usb/`

这是项目最复杂的部分，约 15 个 Kotlin 文件 + NDK C++ 层，实现 libusb 等时（isochronous）USB 音频输出：

- **`UsbExclusiveController`**：状态机驱动入口，协调设备 claim、格式协商、流的启停和错误恢复。
- **`UsbExclusiveStateMachine`**：USB 专属模式的显式状态机（Idle / Claiming / Streaming / Error 等）。
- **`UsbFormatNegotiator`**：解析 UAC 描述符，按采样率/位深/声道匹配最优 alternate setting。
- **`UsbIsoScheduler`** / **`UsbIsoPump`**：等时传输调度与 PCM 环形缓冲泵，`IsoPumpWatchdog` 监控泵是否卡死。
- **`LibusbAudioSink`**：Media3 `ForwardingAudioSink` 子类，将 ExoPlayer PCM 送入 libusb 泵；USB 未就绪时透明回退到系统默认 sink。
- **`UsbNativeDriver`**：JNI 门面，调用 NDK C++ 的 libusb 接口。暂停用"静音门"（不 stop 泵）避免重新 claim 接口造成 EBUSY。
- **`UsbDescriptorParser`**：解析 USB 音频设备描述符。
- **`UsbHardwareVolumeControl`** / **`UsbVolumeCurve`**：UAC Feature Unit 硬件音量控制。
- **`PcmFloatConverter`**：float32 PCM ↔ int16/int24/int32 转换。
- `UsbExclusivePreferences`（DataStore）：用户的独占模式开关与格式偏好设置。

**USB 驱动约定**：libusb 等时泵跑在专用线程，不在 Main/IO dispatcher 上。格式协商失败时回退系统输出，不抛异常。暂停用静音门而非 stopStream，避免 EBUSY。

### 离线与本地下载

两个**不同**的东西，别混（术语定义见 `CONTEXT.md`）：

- **曲库快照 `LibrarySnapshot.kt`**：手机上的「曲库里有哪些歌」清单（JSON，`filesDir/library-snapshot.json`）。**只在线下兜底，不在线上抢活**——能连上 Jellyfin 时一律以服务器为准，请求失败才顶上。覆盖发现 / 全部音乐 / 专辑 + 离线搜索；**播放列表不在快照里**，除非那个列表被下载过（已下载列表另存一份本地清单）。
- **下载 `MediaDownloads.kt`**（原 `MediaCache.kt`）：**用户主动**存到手机上的原文件副本（`SimpleCache`，目录 `filesDir/media_cache`，**不是 `cacheDir`**——后者会被系统清掉）。**播放链路纯只读，绝不写盘**（`CacheDataSource.Factory.setCacheWriteDataSinkFactory(null)`）；写盘只有一条路：`CacheWriter` + `createDataSourceForDownloading()`。**没有自动淘汰**（`NoOpCacheEvictor`），没有总量上限，没有一键清空。**只有完整副本才算数**（`isCompleteCopy`）。

关键约定（ADR-0007 / ADR-0008）：

- `JellyfinUiState.isOffline` 是全局离线态；`goOffline()` 由**任意**网络请求的 `Network` 错误触发（探活/离线可见性全 App 共用），不是某一节自己的事。
- **离线时曲库各屏只显示已下载的东西**（ADR-0008），不再「全量 + 标灰」——置灰那套 UI 已整体删除。过滤是**界面层派生**（`offlineVisibleLibrary()`，纯函数）：原始 `tracks/albums/...` 保持全量，回在线自动恢复，别去改 state（探活回在线时「歌曲」页不会重拉，改 state 会让列表永久缩水）。
- 专辑**有一首已下载就显示**；详情页只列已下载曲目并隐藏下载按钮；离线搜索只搜已下载（`isOfflineSearch` 时必须标明）。`cachedTrackIds` 为 `null` = 还不知道（在线），界面不筛。
- 离线时**播放队列不动**；`isPlayable` / `unplayableReason` 只服务队列里遗留的未下载歌与新点播守门。
- `PulsarApplication.mediaCacheStore` 是**进程级单例**：`SimpleCache` 对同一目录只允许一个实例，且界面（查副本/报占用）与播放服务共用它。**服务销毁时不能释放它**。
- **退出登录不清下载**；token 过期（会话失效）也不清。只有曲库快照会清。
- **下载按「名单」记账**（`DownloadLedger`，DataStore）：删除只删当初下载的那批，服务器后来新增的歌不受影响。**名单 ≠ 状态**——算不算「已下载」永远看字节（`ContentMetadata` 总长 + `isCached`）。
- 下载归属按「服务器地址 + 用户 ID」隔离，但**音频字节共享**（缓存键是播放 URL，不带用户 ID）：两个账号下过同一首，谁删都算删。
- **Media3 `SimpleCache` 三个坑**：①残条目调 `isCached(-1)` 会抛 `IllegalArgumentException`；②构造失败不会立刻崩溃而是延迟；③删资源时要跳过正在播放的 key。

### UI 层

**页面级 Composable 集中在 `MainActivity.kt`（约 4400 行）**；`NowPlayingScreen.kt`（播放页）与 `QQDownloadScreen.kt` 是独立文件。**主题、设计 token、通用组件、领域模型已拆分**：

- `ui/theme/Dimens.kt`：**全局设计 token 的唯一出处**（间距 4/8/12/16/20/24、触摸目标 48/56/64、圆角 16/20）。新 UI 一律取这里的常量，不写魔法 dp。数值量纲参考 Auxio，但**不是抄代码**（见 ADR-0004）。
- `ui/components/LiquidGlass.kt`：**液态玻璃材质的唯一出处**（`liquidGlass` Modifier：模糊 + 透镜 + 高光）。**玻璃面统一走 `GlassPanel`**——它先把前景色提供成 `onSurface` 再套 `liquidGlass`，不要再直接调 `liquidGlass` 或各写各的模糊/透镜数字。全屏播放页 `NowPlayingScreen` 的两处圆钮是已知例外（spec 明确不做那一页）。
- `ui/components/`：`LiquidGlassSlider`、`SurfaceCard`、`VolumeFeedback` / `VolumeFeedbackOverlay` 等可复用组件。
- `ui/theme/CoverPalette.kt` + `CoverTheme.kt`：**按专辑封面取色**染主题（Pulsar 的特色，不是 Auxio 的做法）。取色失败回退默认渐变。
- `ui/theme/ThemePalette.kt`（工单 10 起）：**种子色 → 完整 M3 ColorScheme** 的纯函数推导。主题色板由当前播放封面的鲜艳色推导，手动预设色只做兜底（见 ADR-0005）。**不要引入 `material-color-utilities` 系依赖**——material3 1.4.0 没有公开的种子色 API，第三方坐标要么要求 Gradle 9.8、要么是 JVM 21 变体。
- `domain/model/`：`Track` / `Album` / `Playlist` / `SearchResults`，与网络 DTO 解耦。

**导航（工单 04，对齐 Auxio tab 语义）**：**不引入导航库**。`LibrarySection` sealed interface 作本地导航状态，`TopLevelSections`（`LibrarySection.kt`）定义顶层顺序与文案：

> 发现 / 歌曲 / 专辑 / 艺术家 / 播放列表

搜索、设置、已缓存、详情页（专辑 / 播放列表 / 艺术家组）**不在顶层**，从别处进入。`navIndex()` 决定左右切换方向，`isDetail()` 判断是否详情层。改导航顺序时**只改 `TopLevelSections`**，chip 和占位页标题都从它取。流派页已删除（ADR-0009），不要再加回来。

- **`JellyfinViewModel`**（AndroidViewModel）：单一 `StateFlow<JellyfinUiState>`，通过 `JellyfinViewModelFactory` 注入 `SessionRepository`、`JellyfinApiFactory`、`AuthorizationSink`。向 UI 暴露纯函数式的请求构造器：`streamUrl(track)`、`albumCover(album)`、`trackCover(track)`（未登录/无封面返回 null，UI 据此显示占位）。错误文案集中在 `friendlyError()`。
- **幂等加载模式**：`albumsJob` + `albumsGeneration` 防止重复请求、过期响应失效。`loadAlbums()` 已加载则不重发；库函数通过"字段非空"判断是否已加载，**因此切换账号时必须清空所有缓存字段**。
- **发现页个人化聚合**：`recentlyPlayed` / `mostPlayed` / `recentlyAdded` 来自 Jellyfin 服务端（`SortBy=DatePlayed/PlayCount/DateCreated` + `Filters=IsPlayed`），**不来自本地存储**。三节独立降级：某节失败时字段保持 `null` 并记入 `discoverFailedSections`（界面不再为它显示骨架），其余节照常；三节全失败才给错误+重试。`null` 与空列表语义不同——**`null` = 没拉到（会重试），空列表 = 拉到了但服务端没数据**。
- **搜索防抖**：`updateSearchQuery()` 内置 300ms debounce，空字符串立即清除结果不发请求。
- **`AlbumCover`**：Coil `AsyncImage`，`ImageRequest.Builder` 手动 `addHeader("Authorization", ...)`（从 `JellyfinRequest.headers` 取），`ColorPainter` 做 placeholder/error。
- **`PlayerControls`** / `rememberMediaController`：播放控制与控制器状态轮询（500ms）。
- **多选**：`JellyfinUiState.multiSelectIds`（空 = 非多选）。非空时 `TrackRow` 点击 = 勾选、顶部出现批量操作条。多选模式下隐藏行尾「更多」按钮。
- **浮层锚点栈**：顶部槽位只放导航与选择（玻璃岛、多选条），底部槽位只放瞬时反馈（Snackbar、音量胶囊）。Snackbar 与音量胶囊共用 `Modifier.bottomDockAnchor`（导航栏 inset + 迷你条实测高度 + 外边距）；迷你条用 `onGloballyPositioned` 上报高度（展开面板自然计入），首帧用 `PlayerDockHeight` 兜底。多选条固定在玻璃岛下方、走 `GlassPanel`。**不要再写固定底距（80/320dp 那类）。**

### QQ 音乐下载集成 `server/` + `data/qqdownload/`

「播放器里搜歌 → 电脑下载解密打标签 → 落曲库 → 刷新即听」的完整链路：

- **PC 侧 `E:\nsmic\server\qqmusic_service.py`**（FastAPI，端口 8900，`start.bat` 手动启动）：包装 `E:\music` 的 `qmc2`/`qqmusic_downloader` 现成函数（**只调用不修改**）。单工作线程 + `queue.Queue` 串行下载，每首 sleep 1s 防风控；`process_song` 的 print 噪音用 `redirect_stdout` 捕获存 job，最终路径从 `-> ...` 行解析；连续 3 次「拿不到下载链接」→ `credential_expired`，等 App 端触发 `/api/credential/retry` 重提（需 QQ 音乐客户端运行中）。**落盘 `E:\file\Te_Music`**（Jellyfin 音乐库目录，实时监控开启，落盘即入库）。
- **Android `data/qqdownload/`**：`QQDownloadApi`（接口）/ `QQDownloadDataSource`（Ktor 实现，错误映射 `QQDownloadError`：`ServiceUnreachable`/`CredentialExpired`/`Http`/`InvalidResponse`）；`QQDownloadSettings`（接口）/ `QQDownloadPreferences`（DataStore：服务地址 + 默认音质）。
- **`QQDownloadViewModel`**：与 `JellyfinViewModel` 完全独立（服务地址单独持久化，不参与 Jellyfin 会话生命周期）。搜索 300ms 防抖；任务轮询（有活跃任务时 2s 间隔，出错即停）；`pollGeneration`/`searchGeneration` 防过期协程。
- **UI `QQDownloadScreen.kt`**（独立文件）：导航 `LibrarySection.QQDownload`，顶部玻璃岛有「QQ 下载」chip。未连接显示服务地址设置卡；凭证过期显示横幅 + 重试按钮。专辑行两个图标按钮：选曲（弹窗勾选部分曲目，默认全不选，`song_mids` 走 `/api/download/album`）和整张下载。**服务地址会从 Jellyfin 地址自动推导**（同机、端口 8900），用户一般不用手填；`ServerUrlHistory` 记录登录过的服务器地址供登录页下拉回填。
- 凭证（QQ cookie）只在 PC 侧内存与 `E:\music\qqmusic_cred.json`，**App 不接触 cookie**，接口不回传。
- **整专下载任务必须带歌名**：`qmc2.get_album_songs()` 只返回 mid 不带歌名（且 `E:\music` 不许改），所以服务里用自写的 `_album_song_details()`（同一底层接口、多取 `songInfo.name`）入队带 `title`，否则 App 任务列表回退显示内部 mid。

### 测试 `app/src/test/`

JUnit4 + `kotlinx-coroutines-test`（`runTest`、`advanceUntilIdle`、`advanceTimeBy`）+ Ktor `MockEngine`。测试命名用反引号描述行为。

- **手写 Fake 优先**（不用 Mockito/MockK）：`FakeJellyfinApi`、`FakeSessionRepository`、`FakeAuthorizationSink` 等在测试文件内定义。
- 数据层测试覆盖 URL 格式、Authorization 头、**断言 URL 不含 token**、错误映射。
- `JellyfinViewModelTest`：覆盖会话恢复、Unauthorized 清会话、网络错误保留会话、库请求过期、搜索防抖、会话过期后缓存全部清空等场景。
- `QQDownloadViewModelTest` / `QQDownloadDataSourceTest`：QQ 下载的搜索防抖、音质降级、轮询启停、凭证过期恢复、503→CredentialExpired 映射等。**注意：测试任务一直 running 时轮询无限继续，必须用有界 `advanceTimeBy` 而非 `advanceUntilIdle`**（否则虚拟时间死循环卡死测试进程）。同理，`viewModelScope` 里的无限 `delay` 循环配 `runTest` 虚拟时间会活锁卡死 gradle daemon——**周期循环必须有上限**。
- **纯函数优先拆出来单测**：`DiscoverPicks`（每日推荐选曲）、`LibraryGroups`（按艺术家分组）、`PlaybackLogic`、`PlaybackReporter.positionTicks` 等都不碰 IO，直接喂数据断言。分组规则有明确边界：大小写不敏感归并、空白/`UNKNOWN_ARTIST` 不建组、排序稳定。
- `PlaybackReporterTest`：上报开始/停止的时机、去重、失败静默、`content://` 本地文件不参与上报。
- USB/播放逻辑均有独立测试类（`playback/` 和 `playback/usb/` 目录下）。
- **UI 没有 Compose 测试设施**：布局改动靠真机肉眼验证，不要为样式写 UI 测试。

## 关键约定与红线

- **改动范围尽量小**，不做与任务无关的重构。
- **安全**：token/密码不写日志、不写 URL、不写 UI；不硬编码密钥。
- **不删除用户数据**（DataStore 存储、配置、历史）。
- **不擅自执行** git push、部署、发布、破坏性迁移。
- 命令失败先看报错信息，不硬猜；工具反复报错时停止并询问用户。
- 根目录的 `qq-music-api-v2/`、`node_modules/`、`qrcode*.png`、`qrresp*.json` 是历史实验产物，已 gitignore，**不是项目代码**，不要动它们。`server/_smoke.log`、根目录 `_smoke.log` 是服务运行日志，同理。根目录的 `{var`（0 字节）、`Poppins,Righteous` 目录也是误产物。
- **`docs/archive/` 是归档区**——里面全是写于项目早期、**内容已过时**的阶段性文档（`DESIGN_SYSTEM.md`、`UI_OPTIMIZATION_SUMMARY.md`、`MVP_PLAN.md`、`FONT_DOWNLOAD_GUIDE.md`、`ui-improvement-suggestions.md`），仍提到「UI 全在 MainActivity」这类旧结论。**只作历史参考，不要当成当前事实**，以代码和本文件为准。新的阶段性总结也直接放进这里，不要在根目录另起一份。

## Agent skills

### Issue tracker

工单以本地 markdown 文件存在 `.scratch/<feature-slug>/` 下（本仓库无 git remote）。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个规范 triage 角色的标签字符串（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）保持默认命名。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文（single-context）—— 仓库根 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

已接受的 ADR：

- `0001` 文件管理走 PC 伴生服务，不走 Jellyfin API（改文件内嵌标签才是真改）
- `0002` 删除 = 移到回收站，可恢复
- `0003` 缓存是副本，可以自动淘汰，不受「不删用户数据」约束（**部分被 0007 取代**）
- `0004` UI 参考 Auxio，但**只取设计规格、不取代码**（Auxio 是 GPL-3.0 + View/XML，Pulsar 是 Compose）
- `0005` 主题色板由封面鲜艳色推导，预设色只做兜底
- `0006` 搜索合并（曲库 + QQ 音乐），QQ 下载降为设置二级页
- `0007` **下载取代自动缓存**：用户手动下载、永不自动删、播放链路纯只读（取代 0003 的自动淘汰部分）
- `0008` **离线只显示已下载**：取消「全量 + 标灰」，过滤在界面层派生（部分取代 0007 的离线可见性口径）
- `0009` **删除流派页**：顶层收敛为 5 项，艺术家维度保留，`Album.genres` 数据字段不动（部分取代 0006 的顶层列表）
