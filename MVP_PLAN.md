# Pulsar Android MVP 实施计划

## 目标
创建包名 `com.example.pulsar`、最低 API 26 的 Kotlin Android 应用，完成 Jellyfin 登录、音乐库浏览、Media3 播放、后台媒体控制，以及 USB DAC 是否被 Android 系统识别的状态展示。

## 架构
单模块、Compose UI、ViewModel + StateFlow、Repository + Ktor/Kotlin Serialization、Media3 `MediaSessionService`。第一版依赖 Android 系统音频路由输出 PCM，不承诺 USB 独占、bit-perfect 或绕过 AudioFlinger。

## 阶段
1. 工程配置与基础启动。
2. Jellyfin 会话、音乐库和曲目数据层。
3. Media3 播放服务与系统媒体控制。
4. Compose 登录、音乐库、播放控制 UI。
5. USB 音频路由提示和错误状态。
6. 工具链可用后执行单元测试、Debug 构建、仪器测试和实体设备验证。

## 数据流
登录输入 -> Jellyfin API -> 会话存储 -> 音乐库查询 -> 领域曲目 -> MediaItem -> ExoPlayer -> PCM -> Android 系统音频路由 -> USB DAC（若系统选择该输出）。

## 边界
- 网络错误、认证失败、空音乐库和不支持格式必须转成用户可理解状态。
- Token 不进入日志、UI 或源码；MVP 使用本地 DataStore，后续升级 Keystore 加密包装。
- Jellyfin 可能触发 Direct Stream/Transcode；第一版只展示当前输出路线，不宣称源格式原样输出。
- USB DAC 断开时应用不能崩溃，播放错误交给 Media3 状态处理。

## 当前验证限制
本机检查到 `java`、`gradle`、`adb`、`sdkmanager` 均不可用，且未设置 Android SDK 环境变量。因此当前只能静态检查文件和代码结构，不能运行 Gradle、单元测试、APK 安装或 USB DAC 真机验证。
