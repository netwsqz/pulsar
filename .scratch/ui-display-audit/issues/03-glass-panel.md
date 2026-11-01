# 03 — GlassPanel 玻璃面前景色统一

**What to build:** 深色主题下，所有玻璃面上的文字和图标都能看清——玻璃岛页签、迷你条歌名、展开面板与队列、音量胶囊、多选条、长按菜单不再压着默认黑色。浅色下长按菜单有明确边界。所有玻璃面看起来是同一种材质。

**Blocked by:** 无 — 可立即开始。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §1、§11

- [ ] 深色下上述玻璃面的文字/图标使用 onSurface 系颜色，肉眼可读。
- [ ] 浅色下长按菜单有可见边界，不再是白描边叠白底。
- [ ] 选中页签保持 onPrimary 标签；未选中页签仍可读。
- [x] 所有玻璃面走同一个 GlassPanel，不再各自写模糊/透镜数字。
- [ ] 构建通过；真机深/浅各看一遍。

## Comments

**改了什么**

- `ui/components/LiquidGlass.kt`：新增 `GlassPanel`（薄包装）——先把 `LocalContentColor` 提供成 `onSurface`，再套 `liquidGlass`；`backdrop == null` 时退化为同色平板。模糊半径不开放参数（全 App 统一），只允许透镜按控件尺寸覆盖。所有外壳玻璃面都改走它。
- `ui/components/VolumeFeedbackOverlay.kt`：音量胶囊改 `GlassPanel`（尺寸 168×44 不变），深色下数字不再是默认黑。降级路径（backdrop 为 null）透明度从 0.95 统一成 0.8，该路径生产调用不会走到，已在 KDoc 写明。
- `ui/components/LiquidGlassSlider.kt`：轨道改 `GlassPanel`，删掉重复的空采样源降级分支；透镜仍按胶囊高度收窄。
- `MainActivity.kt`：`GlassChipBar`、`MiniPlayerBar`（含展开面板与队列）改 `GlassPanel`；多选条与长按菜单的 `Surface` 补 `contentColor = onSurface`（带 alpha 的 surface 匹配不上 `contentColorFor`，不给就落回黑色）；长按菜单浅色边框从白色描边改为 `outlineVariant`。
- 选中 chip 的 `onPrimary` 未动（`glassChipColors` 显式设置）；芯片未选中标签仍是 `onSurface`。

**测试结果**

- `:app:testDebugUnitTest` 全绿；`:app:assembleDebug` 通过。本工单为纯样式，无新增测试（spec「不测什么」明确排除玻璃材质/颜色）。
- `/code-review` 两轴已跑：已修「注释承诺全 App 但全屏播放页仍直连 liquidGlass」（注释收窄为外壳 + 例外说明）、删除无调用方的 `blurRadius` 参数、补写降级透明度变化说明。

**commit**

- 未提交：另一窗口正在整理 ADR-0007/0008 基线提交，本工单改动留在工作区，由该窗口一并处理。

**真机待验清单**

1. 深色：玻璃岛未选中 chip 图标、退出图标、迷你条歌名/歌手、展开面板时间与输出信息、队列标题、音量数字、多选条「已选 N 首」、长按菜单文字——全部可读。
2. 浅色：长按菜单有可见边界（灰描边，不再是白描边）；其余玻璃面观感与改前一致。
3. 切换深浅主题：选中 chip 仍是 onPrimary 标签，未选中 chip 仍可读。
4. 360dp：玻璃岛/迷你条/展开面板布局无位移（GlassPanel 只是把原 `liquidGlass` 包了一层 Box）。

**顺带发现（未改）**

- 多选条与长按菜单尚未玻璃化：材质/圆角归工单 04、15（15 需先把菜单从 `Popup` 提到主窗口才能采样 backdrop）。本工单只修了它们的前景色。
- `NowPlayingScreen.kt` 仍直接调用 `liquidGlass`（两处圆钮）：spec「明确不做」排除全屏播放页，未动。
- `CLAUDE.md` 仍写「全 App 的玻璃面都走 liquidGlass」；现在入口是 `GlassPanel`，建议后续统一文档措辞（本轮未改，避免混进基线提交）。
