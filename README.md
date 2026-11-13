# Pulsar

Pulsar 是一个面向普通 Android 手机的 Jellyfin 音乐播放器 MVP。

## 当前范围

- Kotlin + Jetpack Compose + Material 3
- Jellyfin 登录、自动恢复会话、曲库浏览（发现 / 歌曲 / 专辑 / 艺术家 / 播放列表）与合并搜索
- Media3/ExoPlayer 播放、`MediaSessionService` 后台播放、播放队列 / 播放模式 / 睡眠定时
- 下载与离线：手动下载到手机、已下载列表、离线只显示已下载、曲库快照兜底
- USB 独占输出（UAC2，含采样率协商与前台音量键接管）、回放增益
- QQ 音乐导入：PC 伴生服务（`server/`，FastAPI 8900）把歌抓进电脑曲库
- 最低 Android API 26；APK 仅打包 `arm64-v8a`

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
