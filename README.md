# Pulsar

Pulsar 是一个面向普通 Android 手机的 Jellyfin 音乐播放器 MVP。

## 当前范围

- Kotlin + Jetpack Compose
- Jellyfin 登录和音乐库浏览（全部歌曲 / 专辑网格 / 专辑详情）
- 专辑封面图（Coil 加载，无封面时显示占位块）
- Media3/ExoPlayer 播放（顺序 / 单曲 / 随机三种模式）
- `MediaSessionService` 后台播放和系统媒体控制
- 登录会话持久化，启动自动恢复
- 最低 Android API 26

第一版通过 Android 标准音频链路输出 PCM。USB DAC 是否可用由手机系统和 DAC 组合决定；本版本不实现 USB 独占、bit-perfect 或自定义 USB Audio 驱动。

## 构建前提

需要 JDK 17、Android SDK（含对应 Platform/Build Tools）和 Gradle 8.10.2（本项目**没有 Gradle wrapper**）。构建前设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量指向 Android SDK。

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
'/c/Tools/gradle-8.10.2/bin/gradle.bat' --no-daemon :app:assembleDebug
```

## 测试

```bash
# 全部单元测试
'/c/Tools/gradle-8.10.2/bin/gradle.bat' --no-daemon :app:testDebugUnitTest

# 只跑某个测试类
'/c/Tools/gradle-8.10.2/bin/gradle.bat' --no-daemon :app:testDebugUnitTest --tests 'com.example.pulsar.data.jellyfin.JellyfinUrlBuilderTest'
```

打开项目后，也可直接用 Android Studio 同步 Gradle 后运行 `app` 的 debug 配置。
