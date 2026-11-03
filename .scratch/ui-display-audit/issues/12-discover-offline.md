# 12 — 发现页：离线隐藏与空态/刷新细节

**What to build:** 离线时发现页不再显示「最近播放 / 最常播放 / 最近添加」三节，也不给这三节骨架或空态；「每日推荐 / 随机专辑」仍按已下载内容显示，两个都没有时给专用空态。空曲库时文案说「曲库为空」，整页空态只出现一次，提示里的操作真实存在。下拉刷新的转圈落在状态栏和玻璃岛下方；横滑骨架第三张被屏幕裁掉一部分；「换一批」只在加载时转，停下补整圈，不回正。

**Blocked by:** 01（设计 token 收口与设置页底部留白）。

**Status:** ready-for-human（代码完成，待真机验收）

**规格参考：** `spec.md` §7、§9、§11

- [x] 离线时三节服务器历史整个消失，不占骨架也不给空态。（`if (sections.showX || historyLoading(...))` 才 `item{}`；`state.isOffline` 时 `historyLoading` 恒 false、`showX` 恒 false ⇒ 整节不组合）
- [x] 离线时每日推荐/随机专辑仍按已下载内容显示；两者都没有时显示专用空态。（数据来自 `visible.*`；空态文案「离线 · 手机里还没有下载的歌曲」）
- [x] `hasAnySection` 只统计实际会渲染的节，不会因三节历史有缓存而压制整页空态。（`DiscoverSections.hasAnySection` 把三节历史排除在判据之外）
- [x] 空曲库文案说「曲库为空」；整页空态只出现一次；操作提示真实存在。（`DiscoverEmpty` 单一文案出处；`dailyEmpty` 只在 `!showDailyPicks && hasAnySection` 时出现；「设置里刷新曲库」已删）
- [x] 下拉刷新转圈在状态栏和玻璃岛下方，第一帧可见。（indicator 的 `maxDistance` 提为「状态栏 + 玻璃岛占位」，不再叠固定 padding）
- [x] 横滑骨架第三张卡被屏幕裁掉一部分，不被压成一条。（`Row` → 不可滚动 `LazyRow`）
- [x] 「换一批」只在加载时旋转，停止时补到最近整圈；无刷新时无持续动画。（`Animatable` + `LaunchedEffect(albumsLoading)`，不再常驻 `rememberInfiniteTransition`）
- [ ] 构建通过；真机验证在线/离线/空曲库/下拉刷新。

## Comments

### 实现记录（2026-11-04，agent）

**改了什么**

- **新增纯函数 `DiscoverSections.kt` + `DiscoverSectionsTest.kt`（15 个用例）**：把「发现页到底有哪些节」
  从 `DiscoverContent` 里抽出来。`discoverSections(...)` 返回 `showRecentlyPlayed/MostPlayed/RecentlyAdded`
  /`showDailyPicks`/`showAlbums`/`showPageEmpty`，`hasAnySection` **只统计实际会渲染的节**——
  这是报告 #22 的根因（旧代码的 `hasAnySection` 直接读原始 state，离线时三节残留缓存把整页空态压掉）。
  空态文案也收在一处（`DiscoverEmpty` + `message()`），并有用例断言文案里不再出现「刷新曲库」这种
  不存在的入口（报告 #26）。
- **三节历史离线整节不画**（spec §7）：`item{}` 被 `showX || historyLoading(...)` 包住。离线时两个条件
  都是 false，所以连骨架都不组合；数据仍留在 `state` 里，回在线自动恢复。全失败时的 `historyError`
  也加了 `!state.isOffline` 守卫——那三节不显示，谈不上「加载失败」。
- **空态去重**（报告 #25）：`dailyEmpty` 改为只在 `!showDailyPicks && hasAnySection` 时出现，
  整页都空时由底部那一个 `EmptyState` 独占，不再上下两段一样的提示。
- **下拉刷新 indicator**（报告 #23）：从「加 `statusBars` + `GlassIslandTopSpacing` 固定 padding」
  改成设 indicator 的 `maxDistance`。**原因**：indicator 自己会按拖拽进度从顶边往下平移，
  平移上限就是这个 `maxDistance`；再叠固定 padding 会双重偏移，拖到底时被顶到离岛很远处。
  现在 `maxDistance = 状态栏高 + GlassIslandTopSpacing`，落位正好露出在岛下面。
- **横滑骨架**（报告 #24）：`Row` 换成 `userScrollEnabled = false` 的 `LazyRow`。普通 `Row` 在宽度不够时
  会把子项**压窄**（3×140+2×12=444dp 塞不进 328dp，第三张被压成一条），`LazyRow` 保持子项自身宽度、
  由视口裁切。
- **「换一批」转圈**（报告 #30/#70）：删掉常驻的 `rememberInfiniteTransition`（它只要被组合就一直在跑帧），
  改成 `Animatable` + `LaunchedEffect(state.isLoading)`：加载时 `while(true)` 逐圈转，停下时补到
  「下一个不小于当前角度的 360° 整倍数」再 `snapTo` 归一化——不回正，也不再空转。

**验证**

- `:app:testDebugUnitTest`：**704 个用例，0 失败**（新增 `DiscoverSectionsTest` 16 个）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**（含 arm64-v8a NDK 层）。
- TDD：先 RED（`discoverSections` 未定义，编译失败）再补实现。

**代码审查（Standards / Spec 并行两轴）后改的地方**

- **Spec 轴发现一个真 bug（已修）**：indicator 的 `windowInsetsPadding + padding(top=72dp)` 与
  indicator 自身的拖拽平移**叠加**，拖到底时被顶到约 230dp 处，远超 spec「落在玻璃岛下方」的要求。
  改为设 `maxDistance`（单一偏移来源）。
- **Standards 轴**：①抽出 `DiscoverSections` 后仍把同一套 `if` 抄三遍 → 保持三块显式写法
  （试过用字符串 key 的 `when` 收敛，更糟，是 Primitive Obsession，已回退），只把判据统一走
  `sections.showX`；②`discoverSections` 的 `tracksLoaded`/`isLoading` 默认值只有测试便利在用 →
  改为必填参数；③两个离线判据（`state.isOffline` vs `isOfflineFiltered`）混用 → **刻意保留**并写了
  注释说明：前者是「离线就不显示」的组合条件（spec §7 明确不走 `VisibleLibrary` 过滤），
  后者是「列表真被筛过」，只有它才能支撑「只显示已下载」这句提示。

**已知风险 / 未改**

- **`isLoading` 是全局标志**：`DiscoverContent` 的「换一批」转圈用它做触发（与原实现一致），
  而它也由专辑详情、播放列表、登录/恢复会话等加载置位。发现页是独立页签，多数时候不受影响；
  会话恢复/登录时若恰好在发现页会多转一会儿。要彻底修需要给 `JellyfinUiState` 加一个
  发现页专属的加载标志——**超出本单范围**（spec「改动范围尽量要小」），留作后续。
- `refreshDiscoverAlbums()` 仍走 `loadLibrary` 的默认 `before`，即它会置 `isLoading`。
  这是转圈能转起来的原因，但也正是上面那条限制的来源。
- 三节历史的服务端数据在离线期间仍留在 state 里（ADR-0008 的既定口径），只在界面上不画。

**真机待验清单（360dp × 深/浅色各一遍）**

1. 在线进发现页：三节历史（有数据的才出现）+ 每日推荐 + 随机专辑；下拉刷新，转圈出现在状态栏与玻璃岛**下方**。
2. 断网（或飞行模式）进发现页：三节历史**整节消失**（无标题、无骨架、无空态）；每日推荐与随机专辑只列已下载内容。
3. 离线且一首都没下载：发现页给专用空态「离线 · 手机里还没有下载的歌曲」，**只有一处**。
4. 在线但曲库为空：文案是「曲库为空 —— 去搜索页搜歌并下载」，不再提「设置里刷新曲库」，且只出现一次。
5. 等发现页加载时看横滑骨架：第三张卡被屏幕**裁掉一部分**，不是被压成细条。
6. 点「换一批」：图标转圈；请求结束后补完整圈停下，**不回正**；不操作时观察无持续动画（可用开发者选项的 GPU 呈现模式辅助判断）。
7. 恢复联网：三节历史自动回来，不需要重登或重拉。
