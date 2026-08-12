# Pulsar — 「添加到播放队列」功能交接文档

**编写时间**：2025-08-09  
**编写人**：Claude  
**承接人**：待定  

---

## 一、概述

在 Pulsar 曲目列表上新增**长按 → 弹出上下文菜单 → 添加到播放队列**功能。所有改动集中在 `app/src/main/java/com/example/pulsar/MainActivity.kt`，无新增文件。

---

## 二、实现范围

| 页面 | 状态 |
|------|------|
| 全部音乐 (TracksContent) | ✅ 已实现 |
| 专辑详情 (AlbumDetailContent) | ✅ 已实现 |
| 播放列表详情 (PlaylistDetailContent) | ✅ 已实现 |
| 搜索结果 (SearchContent) | ✅ 已实现 |

### 新增 Composable

| 函数 | 行号 | 职责 |
|------|------|------|
| `AddToQueueMenu` | ~1270 | 长按弹出菜单容器，管理整体缩放 + 淡入动画 |
| `StaggeredMenuItem` | ~1330 | 单个菜单项，逐项错峰上滑 + 淡入 |

### 新增回调

```kotlin
// 参数类型
onAddToQueue: (Track) -> Unit

// 传递路径
MusicLibraryScreen → TracksContent / AlbumDetailContent / PlaylistDetailContent / SearchContent
    → TrackRow(onAddToQueue = { onAddToQueue(track) })
```

### 新增 UI 元素

- `SnackbarHostState` + `SnackbarHost`：操作成功提示"已添加到播放队列"
- 菜单动画：`Animatable` 弹性缩放 + `updateTransition` 错峰入口

---

## 三、⚠️ 已知 BUG（必须修复）

### Bug 1: 菜单弹出位置错误

**现象**：长按曲目后，菜单弹出在屏幕左上角，而不是长按位置附近。

**原因**：`AddToQueueMenu` 接收了 `anchor: IntOffset` 参数，但实际调用时传的是 `IntOffset.Zero`（见 `TrackRow` 的 `onLongClick` 回调），且 Popup 的 `offset` 逻辑已被移除（之前用 `firstItemSize` 计算锚点，但 `firstItemSize` 总是 `IntSize.Zero`，等于没偏移）。

**修复方案**（推荐做法：改为居中弹出，省去锚点计算）：

```kotlin
// 在 AddToQueueMenu 中，去掉 anchor 参数，Popup 不加 offset：
Popup(
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true)
) {
    // 内容居中显示
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 现有 Surface 内容...
    }
}
```

或者如果要真正锚定到手指位置，需要：
1. 在 `TrackRow` 的 `onLongClick` 中获取事件坐标（`pointerInput` 或 `onGloballyPositioned`）
2. 传给 `AddToQueueMenu` 做 `Popup(offset = ...)` 计算

### Bug 2: 关闭动画不生效

**现象**：点击菜单项或点击外部关闭时，菜单瞬间消失，没有退场动画。

**原因**：`Popup` 是独立窗口，用 `if (showMenu)` 控制显隐时，Compose 的退出动画 (`AnimatedVisibility`/`Animatable`) 无法在 Popup 被移除前播放完成——Popup 一旦 `showMenu = false`，会立即卸载所有子节点。

**修复方案**：使用 `AnimatedVisibility` 包裹 Popup 内容（但先前试过因 `AnimatedVisibility` 的布局约束导致菜单不显示），或者改用 `ModalBottomSheet` 或 `AlertDialog`。推荐简单方案：

```kotlin
// 方案 A：去掉退场动画，只保留入场动画（最简单的修复）
// 用户感知：弹出有弹性缩放，关闭瞬间消失——体验可以接受

// 方案 B：用 Dialog 替代 Popup（Dialog 有更好的动画支持）
// 但 Dialog 会遮挡状态栏，需要额外处理

// 方案 C：在 Popup 内部用 LaunchedEffect 做延迟关闭
// 在 onClick 中先播完退场动画再关闭：
onClick = {
    scope.launch {
        // 播退场动画
        menuScale.animateTo(0.85f, tween(120))
        menuAlpha.animateTo(0f, tween(120))
        onDismiss()
    }
}
```

### Bug 3: `anchor` 变量未使用（Dead Code）

`TrackRow` 中声明了 `var anchor by remember { mutableStateOf(IntOffset.Zero) }`，但 `onLongClick` 中只赋了 `IntOffset.Zero`，从未被用于计算实际坐标。如果采用 Bug 1 的居中方案，这个变量可以直接删除。

### Bug 4: `StaggeredMenuItem` 的 `delayMs` 参数只有一个菜单项时不生效

目前菜单只有"添加到播放队列"一项，`delayMs = 0` 没有问题。但未来如果加更多菜单项（如"下一首播放""收藏"），需要：
1. 在 `Column` 中为每个项传入递增的 `delayMs`（0, 30, 60...）
2. `StaggeredMenuItem` 的 `alpha` 动画用 `tween(220, delayMillis = delayMs)` 已支持

---

## 四、技术债务

### 1. 边框高光颜色硬编码

```kotlin
border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.6f))
```

这个 `Color.White` 亮色下好看，但暗色模式下会变成灰色边框透白光，效果未知。需要根据 `isSystemInDarkTheme()` 切换：

```kotlin
val borderColor = if (isSystemInDarkTheme()) 
    Color.White.copy(alpha = 0.15f) 
else 
    Color.White.copy(alpha = 0.6f)
```

### 2. `Popupscope` 未使用

`AddToQueueMenu` 中声明了 `val popupScope = rememberCoroutineScope()` 但从未使用。Bug 2 的修复方案 C 会用到它。

### 3. `Icons.Filled.PlaylistAdd` 已废弃

```kotlin
// 当前
Icons.Filled.PlaylistAdd

// 建议改为
Icons.AutoMirrored.Filled.PlaylistAdd
```

### 4. 菜单 Surface 的 alpha 值

`surface.copy(alpha = 0.96f)` 在亮色下接近全白，暗色模式下需要调整为 `surface.copy(alpha = 0.92f)` 或干脆用 `surface` 不加透明。

---

## 五、动画参数参考

### 整体菜单

| 属性 | 值 | 曲线 |
|------|-----|------|
| 初始缩放 | 0.85 | — |
| 目标缩放 | 1.0 | `spring(dampingRatio=0.6f, stiffness=320f)` |
| 初始透明度 | 0 | — |
| 目标透明度 | 1.0 | 同上 |

### 菜单项错峰

| 属性 | 值 | 曲线 |
|------|-----|------|
| 初始 Y 偏移 | 12dp | — |
| 目标 Y 偏移 | 0dp | `spring(dampingRatio=0.7f, stiffness=350f)` |
| 透明度 | 0→1 | `tween(220ms, delay=0/30/60ms)` |
| 错峰间隔 | 30ms | — |

### 视觉风格

| 属性 | 值 |
|------|-----|
| 圆角 | `RoundedCornerShape(16.dp)` |
| 阴影 | `shadowElevation = 16.dp` |
| 表面透明 | `surface.copy(alpha = 0.96f)` |
| 高光描边 | `BorderStroke(0.5.dp, White(0.6))` |
| 菜单项内边距 | `horizontal=20.dp, vertical=14.dp` |
| 图标大小 | `20.dp` |
| 图标与文字间距 | `14.dp` |

---

## 六、设计文档

设计文档位于：
```
E:/nsmic/docs/superpowers/specs/2025-08-09-add-to-queue-design.md
```

内容包含：交互流程、动画设计、添加逻辑分支、改动范围清单。

---

## 七、构建与测试

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE='/c/Tools/gradle-8.10.2/bin/gradle.bat'

# 编译
"$GRADLE" --no-daemon :app:compileDebugKotlin

# 测试
"$GRADLE" --no-daemon :app:testDebugUnitTest

# 打包
"$GRADLE" --no-daemon :app:assembleDebug

# 安装（设备 kngqay9deamvswww）
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s kngqay9deamvswww install -r app/build/outputs/apk/debug/app-debug.apk
```

当前编译状态：✅ 编译通过，单元测试全部通过。

---

## 八、建议修复优先级

| 优先级 | 问题 | 难度 | 建议 |
|--------|------|------|------|
| P0 | Bug 1: 菜单位置 | 低 | 改为居中弹出，去掉 anchor 参数 |
| P0 | Bug 2: 关闭无动画 | 中 | 方案 C（延迟关闭带退场动画） |
| P1 | Bug 3: 死代码 anchor | 低 | 删除 |
| P2 | 技术债务 1: 暗色模式边框 | 低 | 加 isSystemInDarkTheme 判断 |
| P2 | 技术债务 3: 废弃图标 | 低 | 换成 AutoMirrored 版本 |
| P3 | 技术债务 2: 未用变量 | 低 | 删除或保留给未来使用 |