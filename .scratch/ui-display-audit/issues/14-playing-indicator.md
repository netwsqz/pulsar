# 14 — 播放指示器与封面渐变

**What to build:** 暂停播放后，播放指示条完全静止；播放时才动。指示器动画不再逐帧改布局高度，滚动列表不掉帧。40dp 小封面的底部渐变按比例缩小，不再压暗大半块；大封面渐变不超过 32dp。没有播放、没有刷新时，界面不该有持续动画帧。

**Blocked by:** 07（歌曲与搜索列表行）。

**Status:** ready-for-human（代码完成，待真机验收）

**规格参考：** `spec.md` §9

- [x] 暂停时播放指示条完全静止；恢复播放才动。（`rememberPlayingBarProgress` 的暂停分支**不组合** `rememberInfiniteTransition`，直接返回静态进度；播放时才创建）
- [x] 播放指示器动画不改变布局高度；滚动列表时不掉帧。（三根条改 `Canvas` 绘制：画布固定 13×14dp，条高只在绘制阶段变，动画帧只触发重绘、不重排整行）
- [x] 40dp 小封面的底部渐变按比例缩放；大封面渐变不超过 32dp 上限。（`coverScrimHeight` = 封面高度 25%、封顶 32dp；`AlbumCover` 用 `drawWithCache` 按实测高度算：40dp→10dp、48dp→12dp、≥128dp→32dp）
- [x] 无播放、无刷新时没有持续动画帧。（播放指示器是唯一常驻的无限动画，已按 `isPlaying` 收口；「换一批」在工单 12 已改成按 `isLoading` 启停的 `Animatable`，骨架扫光只在加载态组合）
- [x] 构建通过（`:app:testDebugUnitTest` **719 用例 / 0 失败**，`:app:assembleDebug` BUILD SUCCESSFUL）。
- [ ] 真机验证暂停 / 播放 / 滚动 / 小封面（待验收，清单见下方）。

## Comments

### 实现记录（2026-11-05，agent）

**改了什么**

- **指示器只在播放时跑动画**：`rememberPlayingBarProgress(isPlaying)` 分成两条路——暂停直接返回静态进度（4/9/6dp），**组合里连 `InfiniteTransition` 都不存在**；播放时才 `rememberInfiniteTransition`。
  以前是无条件创建，暂停后仍一帧一帧地跑（报告 #71）。
- **条高从布局阶段搬到绘制阶段**：三根条合成一个 `Canvas`，尺寸固定 13×14dp，在 `DrawScope` 里按进度画圆角矩形。
  以前是 `Modifier.height(barHeight)`，动画每帧 remeasure 整行——滚动掉帧的根因。
  进度以「按下标取值的函数」返回，真正的 `state.value` 读取发生在绘制块里，动画帧只重绘。
- **暂停态可辨**：遮罩 0.35 → 0.48（spec §9 的 0.45~0.5），条从 `onSurfaceVariant` 改白色（压在深色遮罩上，多花的封面也看得见）。
- **封面渐变跟封面走**（报告 #8）：`AlbumCover` 的底部渐变从固定 `height(32.dp)` 改成 `drawWithCache` + `matchParentSize`，按实测高度算 25%、封顶 32dp。
  40dp 迷你封面从「大半块压暗」变成 10dp 窄边。
- **纯函数缝**：新增 `ui/components/CoverOverlayGeometry.kt`（`playingBarHeight` / `pausedPlayingBarProgress` / `PlayingBarCount` / `PlayingBarsWidth` / `coverScrimHeight`），
  几何数字集中一处，配 `CoverOverlayGeometryTest`。

**验证**

- `:app:testDebugUnitTest`：**719 用例，0 失败**（新增 `CoverOverlayGeometryTest` 9 条）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**（含 arm64-v8a NDK 层）。
- TDD：先写 `CoverOverlayGeometryTest`（红 = 三个函数不存在、编译失败）再补实现。
- UI 没有 Compose 测试设施（仓库明文约定），动效与观感只能真机验。

**代码审查（Standards / Spec 并行两轴）后改的地方**

- **Standards 轴**：①「三根」这个数散在画布宽度、绘制循环、暂停进度表里 → 提 `PlayingBarCount`；
  ②暂停分支上无收益的 `remember { { … } }` → 去掉，播放分支的 `when(index)` 级联 → 换成 `List<State<Float>>` 索引；
  ③`coverScrimHeight` 里的 `0.25f` / `32.dp` 裸字面量 → 提成命名常量；
  ④新文件注释「以前埋在 draw 代码里」不准确（条高以前是布局代码，只有渐变是画出来的）→ 改口径。
- **Spec 轴**：①条从**顶对齐**变成**逐条居中**——工单没要求改对齐，但原 KDoc 写的就是「居中三根均衡器条」，
  且旧实现里 `Row` 高度逐帧变、整组位置也在抖；保留居中，**真机看这里**（见待验清单第 5 条）；
  ②新增的封面几何纯函数缝超出 spec「要测的缝」清单——按仓库 TDD 强约定保留。

**已知风险 / 未改**

- 系统若把动画时长全局设为 0（开发者选项 / 无障碍），`InfiniteTransition` 会被系统停掉、条静止——这是系统的设计，不做对抗。
- 渐变浓度没动（深色 0.20 / 浅色 0.03，走 `scrim` 恒黑）：本单只改高度，浅色主题下几乎看不见是既有观感。

**真机待验清单（360dp × 深/浅色各一遍）**

1. 播放一首 → 列表当前曲目的封面指示条在跳；点暂停 → **立刻静止**，条变白、遮罩更深；恢复播放再跳。
2. 播放中快速滚动歌曲列表 / 专辑网格：指示条不掉帧（与改前对比）。
3. 迷你条 40dp 小封面：底部渐变只剩窄窄一条；专辑卡片（140dp）与全屏大封面都是 32dp 上限。
4. 暂停后停留 10 秒：用「GPU 呈现模式分析」看静止时是否还在持续出帧、机器是否发烫。
5. 重点看**逐条居中**（新）与原来顶对齐的观感差异是否接受。
