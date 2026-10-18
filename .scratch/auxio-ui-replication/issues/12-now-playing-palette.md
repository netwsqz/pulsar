Status: done（代码完成 + 单测全绿；观感需真机肉眼验收）

# 12 — 全屏播放页接入色板 + 系统栏图标

**What to build:** 全屏播放页的玻璃面、控件底色、进度条改吃新色板，**背景保持现状**（封面渐变 + 模糊封面 + 黑 scrim），文字与图标恒为白色；进入该页时状态栏/导航栏图标强制亮色，退出恢复。

**Blocked by:** 11

## 要做的

- [ ] `NowPlayingScreen` 的玻璃表面色从写死的 `Color.White.copy(alpha = 0.12f)`（`NowPlayingScreen.kt:546/659`）改为色板推导色，保持同样的半透明观感。
- [ ] 控件底色、滑块轨道、进度条走新色板；进度条强调色继续用传入的 `gradientColors.accent`。
- [ ] **背景不动**：`NowPlayingBackground`（`NowPlayingScreen.kt:709-772`）三层保持现状。
- [ ] **文字与图标保持白色系**（`NpTextPrimary/Secondary/Faint`，`NowPlayingScreen.kt:127-129`），不换成 `onSurface`。
- [ ] 进入全屏页时 `isAppearanceLightStatusBars = false` / `isAppearanceLightNavigationBars = false`，退出时恢复为跟随主题。
- [ ] 没封面时，播放页兜底渐变取用户预设色推导（而不是固定品牌靛蓝），与主界面兜底保持一致。

## 验收标准

- [ ] 切歌时播放页玻璃/控件跟着换色，背景仍是封面渐变
- [ ] 浅色系统模式下进入播放页，状态栏图标清晰可见（亮色）
- [ ] 退出播放页后，状态栏图标恢复跟随深浅模式
- [ ] 无封面时播放页背景使用预设色（若用户选过）或默认靛蓝，不出现「控件是新色、背景是旧色」
- [ ] 文字在任何封面下都清晰可读
- [ ] 现有单测全绿

## 实现提示

- 这一页刻意脱离主题（见 `CLAUDE.md` UI 层说明），改动要克制：**只换「面上的颜色」，不换「文字和背景」**。
- 系统栏图标用 `WindowCompat.getInsetsController(window, view)` 设置，用 `DisposableEffect` 在进入/退出时配对。
- `NowPlayingScreen` 已有 `gradientColors: CoverGradient` 入参，扩展时不要破坏它——播放页背景仍然需要它。

## Comments

- Q18=A / Q25=A 的直接落地。
