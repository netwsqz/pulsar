# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

**Pulsar**：单模块 Android Kotlin 应用，面向普通手机的 Jellyfin 音乐播放器 MVP。
技术栈：Jetpack Compose + Material 3、ViewModel + StateFlow、Ktor + kotlinx.serialization（数据层）、Media3/ExoPlayer + MediaSessionService（播放）、DataStore（会话/安装 ID）、Coil（封面图）。minSdk 26 / targetSdk 35 / Java 17。

代码内注释为中文；与用户沟通默认用中文。**本项目是 TDD 强约定**：先写失败测试（RED）→ 最小实现（GREEN）→ 重构。

## 构建与测试命令（Windows）

**关键环境要求：** 项目**没有 Gradle wrapper**，用系统 Gradle 8.10.2；且必须先设置 Android SDK 环境变量，否则报 "SDK location not found"。

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

# 安装到真机（项目约定设备序列号 kngqay9deamvswww，小米机型需在开发者选项开"USB 安装"）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 能直接打开此项目（同步 Gradle 后运行 `app` debug 配置）。

## 架构总览

单 `app` 模块，按层组织，但没有强制分层包；UI 全部集中在 `MainActivity.kt` 一个文件。

### 数据层 `data/jellyfin/`

- **`JellyfinDataSource`**：Ktor 客户端封装。所有网络调用返回 `Result<T>`，错误统一映射为 `JellyfinError` sealed class（`InvalidCredentials` / `Unauthorized` / `Http(statusCode)` / `InvalidResponse` / `Network`）。`execute {}` 包装器把异常转成 `Result.failure`，**`CancellationException` 一律重新抛出**。
- **`JellyfinUrlBuilder`**：纯函数式 URL 构建，**把凭据排除在 URL 之外**是它的核心职责。校验 baseUrl 必须是 HTTPS 或私网 HTTP（`normalizeBaseUrl`）；ID/token 空值抛 `IllegalArgumentException`；路径段做百分号编码（`pathSegment`）。返回 `JellyfinRequest(url, headers)` 结构——token 只进 `Authorization` header，不进 URL。
- **`JellyfinDtos`**：kotlinx.serialization DTO（`@SerialName` 映射 Jellyfin 大写字段）+ `toTrack()` / `toAlbum()` 转换函数。注意：`toAlbum()` 的 imageTag 只取 `imageTags["Primary"]`（不要回退到 Backdrop）。

### 会话与鉴权（跨层安全约定）

- **Token 绝不出现在 URL、日志或 UI**。请求头用 MediaBrowser 格式：
  `MediaBrowser Client="Pulsar", Device="Android", DeviceId="$deviceId", Version="0.1.0", Token="$token"`
- `PulsarApplication.activeAuthorization`（`@Volatile`，仅内存）：登录/恢复登录时写入完整 Authorization 头，退出登录时清空。它是**播放流**和**封面图**共用的鉴权来源。
- 播放流：`AuthHttpDataSourceFactory` 每次创建数据源时从 `activeAuthorization` 读取当前头，重新登录后无需重建播放器。
- `SessionStore`（DataStore）持久化登录会话：只存 token，**不存密码**。
- 安装 ID：`InstallationIdStore`（DataStore），作为 `DeviceId` 传给 Jellyfin。

### 播放 `playback/` + `PlaybackService.kt`

- `PlaybackService`：Media3 `MediaSessionService`，用 `AuthHttpDataSourceFactory` 建 ExoPlayer。`onTaskRemoved` 时若无播放内容则停止服务。
- `PlaybackMode.kt`：三种模式（顺序循环/单曲循环/随机）与 Media3 设置的映射。
- `PlaybackLogic.kt`：`buildPlaybackOrder`（旋转队列使点击的曲目开头）、`formatPosition`（`m:ss`）。
- **USB 音频输出（常开）**：`PlaybackService` 监听 USB 音频设备插拔（`AudioDeviceCallback`），插上小尾巴/DAC 时调 `setPreferredAudioDevice` 强制路由到 USB，拔掉回退系统默认。**没有用户开关**（系统通常已自动路由，显式路由保证任何设备行为一致）。采样率与设备状态写入 `PlaybackDiagnostics` 单例供 UI 展示。真·独占（bit-perfect）调研后暂缓：需引入第三方原生 USB 驱动 + 升级工具链，且未经验证设备多，收益低。

### UI `MainActivity.kt`（单文件 UI 层）

一个文件容纳 ViewModel + 全部 Composable，约 700 行。改动 UI 前先读这个文件。

- **`JellyfinViewModel`**（AndroidViewModel）：单一 `StateFlow<JellyfinUiState>`。私有 `session`/`dataSource` 只在登录/恢复登录后赋值。向 UI 暴露的是纯函数式的请求构造器：`streamUrl(track): String?`、`albumCover(album): JellyfinRequest?`（未登录/无封面返回 null，UI 据此显示占位）。错误文案集中在 `friendlyError()`。
- **导航**：**不引入导航库**。`LibrarySection` sealed interface（`Tracks` / `Albums` / `AlbumDetail(album)`）作本地导航状态，`when` 分支切换内容。
- **幂等加载模式**：`albumsJob` + `albumsGeneration` 防止重复请求、过期响应失效。`loadAlbums()` 已加载则不重发；`LaunchedEffect(section)` 进入分区时触发加载。
- **`AlbumsContent`**：`LazyVerticalGrid(Fixed(2))` 专辑卡片，卡片顶部 `AlbumCover`（`aspectRatio(1f)`）；无封面时显示 `surfaceVariant` 占位色块。
- **`AlbumCover`**：Coil `AsyncImage`，`ImageRequest.Builder` 手动 `addHeader("Authorization", ...)`（从 `JellyfinRequest.headers` 取），`ColorPainter` 做 placeholder/error。
- **`PlayerControls`** / `rememberMediaController`：播放控制与控制器状态轮询（500ms）。

### 测试 `app/src/test/`

JUnit4 + `kotlinx-coroutines-test`（`runTest`）+ Ktor `MockEngine`。测试命名用反引号描述行为。数据层测试覆盖 URL 格式、Authorization 头、**断言 URL 不含 token**、错误映射。播放逻辑与 DataStore 相关逻辑有独立测试类。

## 关键约定与红线

- **改动范围尽量小**，不做与任务无关的重构。
- **安全**：token/密码不写日志、不写 URL、不写 UI；不硬编码密钥。
- **不删除用户数据**（DataStore 存储、配置、历史）。
- **不擅自执行** git push、部署、发布、破坏性迁移。
- 命令失败先看报错信息，不硬猜；工具反复报错时停止并询问用户。
