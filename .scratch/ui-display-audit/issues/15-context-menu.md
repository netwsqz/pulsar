# 15 — 长按菜单同窗玻璃与改名弹窗

**What to build:** 长按菜单和其它玻璃面使用同一种材质、同一种前景色，宽度、行高、圆角与其它弹出层一致；返回键、点外关闭、焦点和 z 序保持原来的行为。改名弹窗的输入区可滚动，键盘弹出时不遮住确认/取消按钮。删除项用警示色，普通项不用。

**Blocked by:** 01（设计 token 收口与设置页底部留白）、03（GlassPanel 玻璃面前景色统一）。

**Status:** ready-for-agent

**规格参考：** `spec.md` §10、§11

- [x] 长按菜单与玻璃岛/迷你条同材质、同前景色；宽高和圆角与其它弹出层一致。
- [x] 返回键、点外关闭、焦点和 z 序与原来的 Popup 行为一致（代码已补齐；真机验证见下方清单）。
- [x] 菜单删除项用警示色，普通项不用。
- [x] 改名弹窗输入区可滚动，键盘不遮住确认/取消按钮。
- [ ] 构建通过；真机验证长按菜单/返回/点外/改名。

## Comments

**改了什么**

- `MainActivity.kt`：长按菜单 `AddToQueueMenu` 从 `Popup`（独立窗口）改成主窗口内的
  `TrackContextMenu` overlay。只有主窗口内才拿得到 `backdrop`，菜单因此能和玻璃岛 / 迷你条走同一个
  `GlassPanel`（同材质、同 `onSurface` 前景色）。宽 240、行高 48、圆角 `Dimens.CornerLarge`、
  内边距 / 阴影安全区统一用 `Dimens.MenuEdgePadding`。
- `Popup` 原本免费提供的四件事手动补齐：全屏透明 scrim（点外关闭）、`BackHandler`（返回键）、
  `FocusRequester`（焦点）、声明在根 Box 最后（z 序）。菜单项动作仍是「立刻执行 + 立刻关」。
- 删除项的图标与文字统一 `error` 色；删除确认按钮也是 `error` 色。普通项保持原来的主色图标 + 默认文字色。
- 菜单状态上提到 `MusicLibraryScreen`（`TrackMenuState`，经 `LocalTrackMenu` 下发）：`TrackRow` 只上报
  曲目与触点，不再各自持有一个 `Popup`。切页时自动收起。
- 改名弹窗：输入列加 `verticalScroll`；`AlertDialog` 传
  `DialogProperties(decorFitsSystemWindows = false)` + `Modifier.imePadding()`——Compose 的 Dialog 在
  默认 `decorFitsSystemWindows = true` 时不设 adjustResize，IME 会盖住弹窗底部（按钮被遮）。

**新增测试**

- `ui/components/TrackMenuGeometry.kt` + `TrackMenuGeometryTest`：菜单定位抽成纯函数 `menuTopLeft`，
  钉死四边夹取、垂直居中、状态栏 / 导航栏 inset 与「窗口比菜单还小」的收口。8 个用例全绿。

**验证了什么**

- `:app:testDebugUnitTest` 全绿；`:app:assembleDebug` 通过。

**「先在一个页面验证再替换全部」的处理**

- 长按菜单在本仓库只有**一个共享实现**（五个列表页 + 详情 / 搜索 / 发现共用同一个 `AddToQueueMenu`），
  所以不存在「只换一个页面」的中间态。本次把共享实现整体替换，导航 / 点外 / 返回 / 焦点的行为在
  所有页面一致；真机验证时先看「歌曲」页，再抽查专辑详情即可。

**真机待验清单**

1. 长按任意歌曲：菜单与玻璃岛同材质、文字可读（深 / 浅色各看一遍）、删除项是红色、普通项不是。
2. 菜单贴近屏幕上 / 下 / 左 / 右边缘长按：整张卡片（含阴影）不出屏，也不压到状态栏 / 导航栏。
3. 菜单打开时按返回键：只关菜单，不退出页面；点菜单外面：关菜单；点菜单内部空白：不关。
4. 「更多」按钮与长按弹的是同一个菜单，锚点不同。
5. 改名：键盘弹出时「保存 / 取消」始终可见，三个输入框可滚动；删除确认按钮是红色。
6. 多选模式下长按：菜单里的「多选」仍正常；离线时长按：没有「改名 / 删除」。

