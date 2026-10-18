Status: done（代码完成 + 单测全绿；观感变化需真机肉眼验收）

# 11 — 主题接线：种子色 → 全 App 色板

**What to build:** 把工单 10 的推导函数接进 `PulsarTheme`，让整个 App 的主题色板跟着当前播放曲目的封面变。**这一票是本次改动里视觉变化最大的一票**——主界面那条高饱和封面渐变会被中性 surface 取代。

**Blocked by:** 10

## 要做的

- [ ] `PulsarTheme` 新增 `seed: Color`（或 `seed: Color?`）参数，内部调 `deriveColorScheme(seed, darkTheme)`；不传种子时用默认色板，行为与改造前一致。
- [ ] `MusicLibraryScreen` 计算种子色：从 `rememberCoverGradient` 拿到的色板里取 **vibrant 种子**（vibrant 为空时回退 dominant，再为空回退用户预设色 / 默认色板）。
- [ ] **主界面背景改为色板 surface 三档渐变**（`surface` → `surfaceVariant` → `background`），替换现在的 `coverGradient.top/middle/bottom`。
  - 保留 `backgroundBrush` + `layerBackdrop` 的结构，玻璃的模糊采样不能坏。
- [ ] **过渡**：只动画实际被用到的十来个角色（`primary` / `onPrimary` / `primaryContainer` / `surface` / `surfaceVariant` / `onSurface` / `onSurfaceVariant` / `outlineVariant` / `tertiary` / `error` 等），800ms，与现有背景渐变同步。其余角色瞬切。
- [ ] **登录页也吃色板**（不加背景渐变）：`PulsarTheme` 在 `LoginScreen` 之上同样生效。
- [ ] `rememberCoverGradient` 的用途收窄到全屏播放页——主界面不再消费它的三色输出，但**不要删函数**。
- [ ] 强调色仍走 `coverAccent`（原色相），继续通过 `LocalCoverAccent` / 显式参数下发给进度条、滑块、均衡器条。

## 验收标准

- [ ] 切歌 → 按钮 / chip / 卡片 / 滑块 / 文字色阶整体换色，800ms 平滑，无闪跳
- [ ] 翻专辑网格 / 进详情页 → 配色**不变**（只有真正切歌才变）
- [ ] 无封面、黑白封面、未登录 → 回退默认靛蓝色板，不残留上一首的颜色
- [ ] 主界面背景不再是高饱和封面渐变，而是带封面色调的中性 surface
- [ ] 玻璃岛 / 迷你条 / 卡片的模糊与描边在新背景上仍然成立
- [ ] 深浅两种系统模式下都正常
- [ ] 现有单测全绿（`:app:testDebugUnitTest`）

## 实现提示

- `MainActivity.kt:909-930` 是现有的渐变 + 动画接线，改动集中在这里。
- `MainActivity.kt:919-923` 的 `backgroundBrush` 覆盖所有登录后页面；`MainActivity.kt:941` 是它唯一的消费点。
- `isSystemInDarkTheme()` 在 `MainActivity.kt:542/573/911/1831/3951` 散落调用，**本票不重构它们**，只保证色板跟随同一判断。
- `NowPlayingScreen` 的入参 `gradientColors` 暂时保持不变（工单 12 处理）。

## Comments

- 这一票做完，主界面观感会明显变化，建议真机验收后再动工单 12。
- 实现完成（代码层）：`PulsarTheme(seed = ...)` + `rememberCoverGradient` 改为返回 `CoverTheme`（渐变 + 种子）；主界面背景改用 `surface → surfaceVariant → background`。单测全绿，**待真机肉眼验收**。
- 种子/强调色/播放页渐变统一取 vibrant（ADR-0005：同一封面只允许一个主色）；`coverGradient` 新增可选 `dominant` 参数，默认行为不变。
- `surfaceContainer*` / `inverse*` 已补推导（弹窗/菜单/Snackbar/Switch 内部会用），单测覆盖明度单调；`scrim` 继续用 M3 默认值，全仓无使用点。
- 预设色回退（`preset`）未接，等工单 13 提供预设色源。
