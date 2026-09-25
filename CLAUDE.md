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

单 `app` 模块，按层组织，但没有强制分层包；UI 全部集中在 `MainActivity.kt` 一个文件。

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
- `UsbAudioOutput.kt`：USB 音频设备插拔监听与路由（插 DAC 强制 `setPreferredAudioDevice`，拔掉回系统默认）。
- **USB 独占下的音量键**（`RemoteVolumePlayer.kt` + `ForegroundVolumeKeys.kt`，详见 `docs/usb-volume-key-interception.md`）：独占模式绕过 AudioFlinger，系统音量对输出无效，音量真源是 `UsbExclusivePreferences.volumePercent`。两条接管路径——
  - **标准路**：`RemoteVolumePlayer`（`ForwardingPlayer` 子类）让 Media3 自动注册 `VolumeProvider`，系统把音量键交给 App。给 AOSP 设备用；**MIUI 上整条失效**（ROM 保留了 `setPlaybackToRemote` 的 API 却删掉了音量键派发，App 侧绕不过去）。
  - **前台路（真正干活的那条）**：`MainActivity.dispatchKeyEvent` 吃掉 `KEYCODE_VOLUME_UP/DOWN` → 自定义命令 `ADJUST_VOLUME`（**传 ±1 档，不传绝对值**）→ `PlaybackService.applyUsbVolume`。只在 Pulsar 位于前台时有效，息屏/后台调不了。
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

### UI 层

UI Composable 集中在 `MainActivity.kt` 一个文件（约 2500 行）；ViewModel/状态已拆成独立文件（`JellyfinViewModel.kt`、`JellyfinUiState.kt`、`JellyfinViewModelFactory.kt`、`QQDownloadViewModel.kt`、`QQDownloadScreen.kt`）。改动 UI 前先读相关文件。

- **`JellyfinViewModel`**（AndroidViewModel）：单一 `StateFlow<JellyfinUiState>`，通过 `JellyfinViewModelFactory` 注入 `SessionRepository`、`JellyfinApiFactory`、`AuthorizationSink`。向 UI 暴露纯函数式的请求构造器：`streamUrl(track)`、`albumCover(album)`、`trackCover(track)`（未登录/无封面返回 null，UI 据此显示占位）。错误文案集中在 `friendlyError()`。
- **导航**：**不引入导航库**。`LibrarySection` sealed interface（`Discover` / `Tracks` / `Albums` / `Playlists` / `Search` / `QQDownload` / `Settings` / `AlbumDetail(album)` / `PlaylistDetail`）作本地导航状态，`when` 分支切换内容。
- **幂等加载模式**：`albumsJob` + `albumsGeneration` 防止重复请求、过期响应失效。`loadAlbums()` 已加载则不重发；库函数通过"字段非空"判断是否已加载，**因此切换账号时必须清空所有缓存字段**。
- **搜索防抖**：`updateSearchQuery()` 内置 300ms debounce，空字符串立即清除结果不发请求。
- **`AlbumCover`**：Coil `AsyncImage`，`ImageRequest.Builder` 手动 `addHeader("Authorization", ...)`（从 `JellyfinRequest.headers` 取），`ColorPainter` 做 placeholder/error。
- **`PlayerControls`** / `rememberMediaController`：播放控制与控制器状态轮询（500ms）。

### QQ 音乐下载集成 `server/` + `data/qqdownload/`

「播放器里搜歌 → 电脑下载解密打标签 → 落曲库 → 刷新即听」的完整链路：

- **PC 侧 `E:\nsmic\server\qqmusic_service.py`**（FastAPI，端口 8900，`start.bat` 手动启动）：包装 `E:\music` 的 `qmc2`/`qqmusic_downloader` 现成函数（**只调用不修改**）。单工作线程 + `queue.Queue` 串行下载，每首 sleep 1s 防风控；`process_song` 的 print 噪音用 `redirect_stdout` 捕获存 job，最终路径从 `-> ...` 行解析；连续 3 次「拿不到下载链接」→ `credential_expired`，等 App 端触发 `/api/credential/retry` 重提（需 QQ 音乐客户端运行中）。**落盘 `E:\file\Te_Music`**（Jellyfin 音乐库目录，实时监控开启，落盘即入库）。
- **Android `data/qqdownload/`**：`QQDownloadApi`（接口）/ `QQDownloadDataSource`（Ktor 实现，错误映射 `QQDownloadError`：`ServiceUnreachable`/`CredentialExpired`/`Http`/`InvalidResponse`）；`QQDownloadSettings`（接口）/ `QQDownloadPreferences`（DataStore：服务地址 + 默认音质）。
- **`QQDownloadViewModel`**：与 `JellyfinViewModel` 完全独立（服务地址单独持久化，不参与 Jellyfin 会话生命周期）。搜索 300ms 防抖；任务轮询（有活跃任务时 2s 间隔，出错即停）；`pollGeneration`/`searchGeneration` 防过期协程。
- **UI `QQDownloadScreen.kt`**（独立文件）：导航 `LibrarySection.QQDownload`，顶部玻璃岛有「QQ 下载」chip。未连接显示服务地址设置卡；凭证过期显示横幅 + 重试按钮。专辑行两个图标按钮：选曲（弹窗勾选部分曲目，默认全不选，`song_mids` 走 `/api/download/album`）和整张下载。
- 凭证（QQ cookie）只在 PC 侧内存与 `E:\music\qqmusic_cred.json`，**App 不接触 cookie**，接口不回传。
- **整专下载任务必须带歌名**：`qmc2.get_album_songs()` 只返回 mid 不带歌名（且 `E:\music` 不许改），所以服务里用自写的 `_album_song_details()`（同一底层接口、多取 `songInfo.name`）入队带 `title`，否则 App 任务列表回退显示内部 mid。

### 测试 `app/src/test/`

JUnit4 + `kotlinx-coroutines-test`（`runTest`、`advanceUntilIdle`、`advanceTimeBy`）+ Ktor `MockEngine`。测试命名用反引号描述行为。

- **手写 Fake 优先**（不用 Mockito/MockK）：`FakeJellyfinApi`、`FakeSessionRepository`、`FakeAuthorizationSink` 等在测试文件内定义。
- 数据层测试覆盖 URL 格式、Authorization 头、**断言 URL 不含 token**、错误映射。
- `JellyfinViewModelTest`：覆盖会话恢复、Unauthorized 清会话、网络错误保留会话、库请求过期、搜索防抖、会话过期后缓存全部清空等场景。
- `QQDownloadViewModelTest` / `QQDownloadDataSourceTest`：QQ 下载的搜索防抖、音质降级、轮询启停、凭证过期恢复、503→CredentialExpired 映射等。**注意：测试任务一直 running 时轮询无限继续，必须用有界 `advanceTimeBy` 而非 `advanceUntilIdle`**（否则虚拟时间死循环卡死测试进程）。
- USB/播放逻辑均有独立测试类（`playback/` 和 `playback/usb/` 目录下）。

## 关键约定与红线

- **改动范围尽量小**，不做与任务无关的重构。
- **安全**：token/密码不写日志、不写 URL、不写 UI；不硬编码密钥。
- **不删除用户数据**（DataStore 存储、配置、历史）。
- **不擅自执行** git push、部署、发布、破坏性迁移。
- 命令失败先看报错信息，不硬猜；工具反复报错时停止并询问用户。
- 根目录的 `qq-music-api-v2/`、`node_modules/`、`qrcode*.png`、`qrresp*.json` 是历史实验产物，已 gitignore，**不是项目代码**，不要动它们。`server/_smoke.log`、根目录 `_smoke.log` 是服务运行日志，同理。

## Agent skills

### Issue tracker

工单以本地 markdown 文件存在 `.scratch/<feature-slug>/` 下（本仓库无 git remote）。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个规范 triage 角色的标签字符串（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）保持默认命名。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文（single-context）—— 仓库根 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
