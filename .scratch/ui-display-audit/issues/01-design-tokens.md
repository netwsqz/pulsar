# 01 — 设计 token 收口与设置页底部留白

**What to build:** 使用者感受不到零散的间距数字，只看到统一的外壳节奏：浮层边距一致、每个页面底部都能滚到播放条上方，设置页最后一张「下载」卡不再被迷你条压住。实现上把外壳尺寸收进全局 token，并清掉重复字面量。

**Blocked by:** 无 — 可立即开始。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §13、§14、§2

- [x] `Dimens` 提供外壳 token：浮层左右/上下边距、玻璃岛高度与顶距、播放条高度与封面尺寸、底部内容留白、队列行最小高度、菜单边距。
- [x] 主代码中不再出现 `96.dp` 作为底部留白；浮层 12/8、菜单 16、登录页 24/16、错误/空态间距改走 token。
- [ ] 设置页滚动到底时，最后一张「下载」卡完整出现在迷你条上方，不被遮挡。
- [x] `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过。

## Comments

### 实现记录（2026-11-01，agent）— commit `5c57911`

**改了什么**

- `ui/theme/Dimens.kt` 新增 9 个外壳 token：`FloatingHorizontalMargin=12`、`FloatingVerticalMargin=8`、
  `GlassIslandHeight=56`、`GlassIslandTopSpacing=56+8×2=72`、`PlayerDockHeight=64`、`PlayerDockCover=40`、
  `BottomContentInset=96`、`QueueRowMinHeight=48`、`MenuEdgePadding=16`。
  `GlassIslandTopSpacing` 由岛高 + 上下外边距推导，改岛高时占位自动跟随。
- `MainActivity.kt`：删除顶层 `GlassIslandTopSpacing` 常量（迁到 Dimens）；13 处 `96.dp` 底部留白换
  `BottomContentInset`；玻璃岛/迷你条/Snackbar 浮层 12/8 换 token；音量胶囊与多选条的「岛下 8dp」
  换 `FloatingVerticalMargin`；长按菜单阴影安全区与内容内边距统一用 `MenuEdgePadding`；登录页 24/16、
  错误/空态 16/12 换 spacing token；玻璃岛高度用 `GlassIslandHeight`；迷你条封面用 `PlayerDockCover`。
- `QQDownloadScreen.kt`：底部留白同步换 `BottomContentInset`。
- 设置页（`SettingsContent`）滚动容器补 `BottomContentInset`。**加在 `verticalScroll` 之后**——
  加在可视区上是固定空隙、滚不动；加在滚动内容里最后一张「下载」卡才能滚到迷你条上方。
  内容区本身已有 `navigationBarsPadding`（MainActivity.kt:1044），迷你条占「导航栏 + 8 + 64」，
  96dp 留白后仍有 16dp 净空。

**验证**

- `:app:testDebugUnitTest`（`--rerun-tasks` 强制重跑）：**666 个用例，0 失败**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**。
- 全仓库 `grep 96.dp app/src`：只剩 `Dimens.kt` 里 token 定义本身（工单口径是「不再作为底部留白」）。
- 本单没有纯函数测试缝（全是布局尺寸），按 spec「不测什么」不硬造 Compose 测试。

**真机待验（清单第 3 项）**

1. 设置页滚到底：最后一张「下载」卡完整出现在迷你播放条上方，不被压住。
2. 手势导航与三键导航各看一次（导航栏高度不同）。
3. 顺带扫一眼：玻璃岛/迷你条/多选条边距没有肉眼可见变化（本次只换 token，数值未变）。

**顺带发现（未改）**

- 多选操作条左右仍是 `16.dp`、圆角 `RoundedCornerShape(16.dp)`、Snackbar 底部仍是 `80.dp`：
  属工单 04（浮层锚点栈）/ 06（玻璃岛）范围，未在本单改。
- `PlayerDockHeight` / `QueueRowMinHeight` 目前只有定义没有调用点，消费方是工单 04 / 05
  （工单清单第 1 项要求本单先把 token 定下来）。

