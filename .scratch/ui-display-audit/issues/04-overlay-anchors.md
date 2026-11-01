# 04 — 浮层锚点栈

**What to build:** 底部浮层不再互相打架：Snackbar 出现在迷你条上方，展开面板展开时也跟着上移；手势导航和三键导航都不遮挡；音量胶囊移到底部，多选时仍能和顶部的多选条同时看见；多选条改用与玻璃岛一致的材质和圆角。

**Blocked by:** 01（设计 token 收口与设置页底部留白）、03（GlassPanel 玻璃面前景色统一）。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §2

- [x] Snackbar 在迷你条上方；展开面板展开时随实测高度上移，不压住条。
- [x] 手势导航和三键导航下 Snackbar 都不被导航栏挡住。
- [x] 音量胶囊出现在底部 overlay 栈；多选时与多选条同时可见，不互相完全遮挡。
- [x] 多选条固定在玻璃岛下方，材质/圆角与玻璃岛一致，左右边距来自 token。
- [x] 首帧没有可感知的 Snackbar 跳动。
- [ ] 构建通过；真机验证两种导航模式。

## Comments

### 实现记录（2026-11-01，agent）

**改了什么**

- **底部锚点栈**：`MainActivity.kt` 新增 `Modifier.bottomDockAnchor(dockHeight)`——导航栏 inset +
  迷你条实测高度 + 两段 `FloatingVerticalMargin`。实测高度只含玻璃条本身（`onGloballyPositioned`
  挂在播放条 8dp 外边距之内），所以两段边距分别是「条距屏幕的下边距」和「浮层与条之间的空隙」，
  与报告 §4 的 `bottom = 8 + measuredDockHeight + 8` 一致。**Snackbar 与音量胶囊共用它**。
- **迷你条上报高度**：`MiniPlayerBar` 的参数 `onExpandedChange: (Boolean)` 换成
  `onDockHeightChange: (Dp)`，在 `GlassPanel` 的 modifier 里 `onGloballyPositioned` 上报。
  展开面板属于同一个 `GlassPanel`，高度天然包含面板；展开/收起动画期间逐帧回调，浮层跟着上移。
  `playerExpanded` 状态、固定 `320.dp` 抬升、硬编码 `80.dp` 底距全部删除。
- **首帧兜底**：状态初值取 `Dimens.PlayerDockHeight`（64dp）= 折叠态实测高度
  （48dp 按钮 + 上下 8dp），所以首个 Snackbar 不会跳。
- **音量胶囊移到底部**：不再用 `statusBarsPadding + GlassIslandTopSpacing` 挂顶部，
  改走同一个 `bottomDockAnchor`。多选条占顶部槽位时两者同时可见。
- **性能取舍**：`playerDockHeight` 以 `State<Dp>` 传给两个小包装 Composable
  （`DockAnchoredSnackbar` / `DockAnchoredVolumeFeedback`），读值发生在小组件内部；
  若在主界面本体读，展开动画每帧会拖着整个 `MusicLibraryScreen` 重组。
- **多选条玻璃化**：`Surface` 换成 `GlassPanel`（表面 alpha 0.8，与玻璃岛同档）、默认 20dp 面板圆角、
  高 `Dimens.SelectionBarHeight`（= `GlassIslandHeight` 56）、左右 `FloatingHorizontalMargin` (12)、
  内边距 `SpaceMedium` / `SpaceTiny`、三个动作 `TouchTargetSmall` (48dp)。
- **文档**：`VolumeFeedbackOverlay.kt` 中「位置在顶部玻璃岛正下方」的过时 KDoc 已同步为底部锚点栈；
  `CLAUDE.md` 的 UI 层补了一条「浮层锚点栈」约定（顶部放导航/选择，底部放瞬时反馈，禁止固定底距）。
- **代码审查后修正**：抽出重复两次的锚点 modifier；更新上述过时 KDoc。审查提出的另外三点未改——
  `surface alpha 0.8` 保持各面自定（`LiquidGlass.kt` 明确表面色是逐调用方的可读性取舍）、
  `playerDockHeight` 命名不追加 `State` 后缀（`remember { mutableStateOf(...) }` 已足够明确）、
  Snackbar 与音量胶囊同槽位重叠（spec §2 要求「共用」锚点，按规格实现）。

**验证**

- `:app:testDebugUnitTest`：**664 个用例，0 失败**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**。
- 本单没有新增单测：仓库无 Compose UI 测试设施，spec「不测什么」明确排除 Snackbar 位置/间距/玻璃材质；
  改动是布局与组合，没有可测的纯函数缝。
- `/code-review` 两轴已跑（Standards / Spec 并行子代理），均未发现阻塞性缺陷；修正项见上。

**真机待验清单（两种导航模式各做一遍）**

1. 手势导航与三键导航：触发任意 Snackbar（如「已添加到播放队列」），确认完整显示在迷你条上方、
   不被系统导航栏挡住。
2. 展开迷你条小面板（右下箭头），再触发 Snackbar，确认它跟着面板上移、不压住面板；
   收起时平滑落回。
3. 多选任意歌曲：多选条在玻璃岛下方、材质/圆角与岛一致；此时按音量键（USB 独占播放中），
   音量胶囊出现在底部，与多选条同时可见。
4. 首帧：冷启动后立刻触发一次 Snackbar，确认没有上下跳动。
5. **残余风险（真机顺带看一眼）**：全屏播放页打开时调 USB 音量，胶囊现在落在底部
   （导航栏上方约 80–124dp），可能压住全屏页底部的时间/进度区。spec 明确不做全屏播放页改版，
   本轮不处理；若观感不可接受，另开单把全屏页纳入锚点栈。
6. Snackbar 正在显示时按音量键，胶囊会盖住 Snackbar 中段（spec 要求两者共用同一锚点）；
   确认这个短暂重叠可接受。
