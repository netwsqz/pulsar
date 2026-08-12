# 添加到播放队列 — 设计文档

## 概述

在全部音乐、专辑详情、播放列表详情、搜索结果页的曲目行上，通过长按弹出菜单将单曲添加到当前播放队列，不中断当前播放。

## 交互

### 触发方式

- 曲目行**长按 300ms** 触发上下文菜单
- 长按时有触觉反馈（`HapticFeedbackType.LongPress`）
- 菜单锚定在曲目行长按位置

### 菜单内容

- 菜单项：**"添加到播放队列"**
- 图标：`Icons.Filled.PlaylistAdd`（或 `QueueMusic` 等 Material 图标）
- 菜单项高度 48dp，符合 Material 3 触控目标

### 反馈

- 操作成功后底部弹出 Snackbar：**"已添加到播放队列"**，1.5 秒自动消失
- Snackbar 出现在 MiniPlayerBar 上方，不遮挡播放条

## 动画设计

### 菜单弹出（浅色主题）

使用 `Popup` + `AnimatedVisibility` 自定义，放弃 `DropdownMenu`（不可定制动画）。

| 阶段 | 属性 | 动画曲线 | 说明 |
|------|------|----------|------|
| 弹出 | 缩放 | `spring(dampingRatio = 0.7f, stiffness = 300f)` | 从 0.85 → 1.0，弹性落位 |
| 弹出 | 透明度 | 同上 | 0 → 1，与缩放同步 |
| 弹出 | 位移 Y | `spring` | 从 -8.dp → 0，微向上带入 |
| 关闭 | 缩放 | `tween(120, easing = FastOutSlowInEasing)` | 从 1.0 → 0.9 |
| 关闭 | 透明度 | 同上 | 1 → 0 |
| 菜单项 | 逐项入场 | 每项延迟 30ms，依次淡入 + 微上移 | 像珍珠一颗颗落下来 |

### 菜单视觉（浅色主题）

- 背景：白色 `surface`，80% 不透明度，`RoundedCornerShape(12.dp)`
- 阴影：`elevation = 8.dp`，柔和扩散
- 每项：`padding(horizontal = 16.dp, vertical = 12.dp)`，图标 + 文字并排
- 按下高亮：`primaryContainer` 背景 + `onPrimaryContainer` 文字色
- 无分隔线，菜单项之间自然留白

## 添加逻辑

### 构建 MediaItem

复用 `playTracks` 中的 Builder 逻辑：

```kotlin
MediaItem.Builder()
    .setUri(streamUrl(track))
    .setMediaId(track.id)
    .setTag(track.imageTag)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .build()
    )
    .build()
```

### 分支逻辑

```
if 队列为空 (mediaItemCount == 0)
    → controller.setMediaItem(item)
    → controller.prepare()
    → controller.play()        // 自动播放
else
    → 计算插入位置 = currentMediaItemIndex + 1
    → controller.addMediaItem(insertIndex, item)
    → 不改变播放状态（继续当前播放/暂停）
```

### 备注

- 不更新 ViewModel 的 `_playbackTracks`：MediaItem 自带的元数据在 `onMediaItemTransition` 的 `toRecoveredTrack()` 中已足够兜底
- 插入位置为当前曲目之后，符合"插队"语义

## 改动范围

单文件：`app/src/main/java/com/example/pulsar/MainActivity.kt`

### 改动清单

| # | 位置 | 改动 |
|---|------|------|
| 1 | `TrackRow` | 新增 `onAddToQueue: (() -> Unit)?` 参数；用 `combinedClickable(onLongClick)` 替换 `clickable`；内嵌 `Popup` + `AnimatedVisibility` 菜单 |
| 2 | `TracksContent` | 新增 `onAddToQueue: (Track) -> Unit` 参数，传给每行 |
| 3 | `AlbumDetailContent` | 同上 |
| 4 | `PlaylistDetailContent` | 同上 |
| 5 | `SearchContent` | 同上（仅歌曲分组） |
| 6 | `MusicLibraryScreen` | 实现 `onAddToQueue` lambda；添加 `SnackbarHostState` + `SnackbarHost` |
| 7 | 预览/骨架屏 | 不受影响，无需改动 |

### 对外接口

```kotlin
// 新增回调类型
onAddToQueue: (Track) -> Unit

// 传递路径
MusicLibraryScreen → TracksContent / AlbumDetailContent / PlaylistDetailContent / SearchContent
    → TrackRow(onAddToQueue = { ... })
```

## 测试要点

- 无新增数据层/播放逻辑层代码，现有单元测试不受影响
- 手动验证：长按曲目 → 菜单弹出动画 → 点击"添加到播放队列" → Snackbar 提示 → 队列中可见新曲目
- 边界：空队列添加后自动播放；队列非空时插在下一首位置；添加多首时顺序正确