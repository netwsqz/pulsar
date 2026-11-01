# 05 — 迷你播放条减法与队列面板

**What to build:** 迷你播放条做减法：360dp 屏上歌名有足够宽度，折叠态只保留封面、歌名/歌手、播放/暂停、展开；没有在播内容时播放键是灰的、点了没反应；展开后的队列标题和移除按钮都够大、层级清楚；空态封面不再是直角灰块。

**Blocked by:** 01（设计 token 收口与设置页底部留白）、03（GlassPanel 玻璃面前景色统一）。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §4

- [x] 360dp 宽屏文字列 ≥160dp；歌名显示明显变长。
- [x] 折叠条只保留封面、歌名/歌手、播放/暂停、展开；上一首/下一首不再出现，全屏页与横滑切歌手势仍可用。
- [x] 无媒体时播放/暂停禁用且无反应。
- [x] 队列分区标题与歌名在字号/字重上可区分；标题行和移除按钮热区 ≥48dp，移除图标保持 16dp。
- [x] 没有在播内容时，迷你条封面是与其它封面一致的圆角占位。
- [ ] 构建通过；真机验证。

## Comments

### 实现记录（2026-11-01，agent）

**改了什么**

- **迷你条做减法**：`MainActivity.kt` 的 `MiniPlayerBar` 删除折叠态「上一首 / 下一首」两个按钮与对应的
  `SkipPrevious` / `SkipNext` 导入，只保留封面、歌名/歌手、播放/暂停、展开。整条横滑切歌与全屏页的
  上一首/下一首保持原样（`NowPlayingScreen.kt` 未动）。360dp 屏文字列 = 360 − 24 外距 − 24 内距 −
  40 封面 − 12 间距 − 2×48 按钮 = **164dp**（ticket 要求 ≥160dp）。
- **播放键守门**：`hasMedia` 从 `durationMs > 0` 改为 `controller != null && controllerState.queue.isNotEmpty()`
  ——时长未上报时不再先灰一下。队列快照在 `onMediaItemTransition` 里也刷新一次，点歌后按钮立刻可用，
  不用等 500ms 轮询。进度条仍要求 `durationMs > 0`（`canSeek`），沿用原有行为。
  **禁用按钮不消费点击**，会漏给整条（打开全屏页）；无媒体时在播放键上盖一层透明
  `pointerInput` 热区把点击吃掉，保证「点了确实没反应」。
- **队列面板层级与热区**：分区标题从 `titleSmall` 改为 `titleMedium`（SemiBold，对比队列项的
  `titleSmall` Medium）；标题行 `heightIn(min = Dimens.QueueRowMinHeight)`（48dp）整行可点；
  移除按钮从 40dp 改回 `TouchTargetSmall`（48dp），图标保持 `16.dp`；队列行同样补 48dp 最小高度。
- **空态封面**：`MiniPlayerCover` 无曲目时改为 `AlbumCover(request = null, …)`，自动获得与列表封面
  一致的 8dp 圆角 + 阴影，不再是直角灰块。
- **Token 收口**（报告 #4/#58/#62/#63）：迷你条/展开面板内的 12/8/4/16/48/20dp 换成
  `Dimens.SpaceMidMedium / SpaceSmall / SpaceTiny / SpaceMedium / TouchTargetSmall / QueueRowMinHeight /
  CornerExtraLarge`；队列列表限高 `240.dp` 新增 `Dimens.QueueListMaxHeight`。16dp 移除图标与 3dp 进度线
  按 `Dimens` 约定属于组件内部尺寸，保持字面量。

**验证**

- `:app:testDebugUnitTest`：**664 个用例，0 失败**（改动前后同为 664）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**。
- 本单没有新增单测：仓库无 Compose UI 测试设施，spec「不测什么」明确排除间距/字号/颜色/手势；
  改动是布局与组合，没有可测的纯函数缝。

**代码审查后修正**

- Standards：空态注释里的「歌单」改为 glossary 词「AlbumCover 的空占位」；`QueuePanelMaxHeight`
  更名为 `QueueListMaxHeight`（它只限队列列表，不是整个面板）；`QueueRowMinHeight` 同时用于队列行。
- Spec：原实现依赖 500ms 轮询刷新 `hasMedia`，点歌后第一拍按钮仍可能灰——已在
  `onMediaItemTransition` 同步刷新队列快照；禁用播放键的点击会漏给整条打开全屏页——已加透明热区拦截。

**真机待验清单（360dp × 深/浅色各一遍）**

1. 播放中（或不播放）看迷你条：只有封面、歌名/歌手、播放/暂停、展开；歌名明显比改动前长，不截成
   四五个字；整条左/右横滑仍能切上一首/下一首。
2. 冷启动未播放：播放键是灰的；点它没有反应（不会打开全屏页）；点条上其它区域仍能打开全屏页。
3. 点一首歌开始播放：播放键立刻变亮可点，不用等半秒；暂停/继续正常。
4. 展开面板 → 播放队列：标题比歌名明显更大更粗；点标题行能展开/收起；移除按钮 48dp 好点，
   图标仍是 16dp；长按拖动排序正常。
5. 无在播内容时看迷你条封面：圆角灰占位，与列表封面占位一致。
6. 全屏播放页的上一首/下一首仍可用。
