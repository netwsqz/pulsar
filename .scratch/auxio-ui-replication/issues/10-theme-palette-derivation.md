Status: done（代码完成 + 584 项单测全绿；纯逻辑工单，验收即单测）

# 10 — 色板推导纯函数

**What to build:** 从一个种子色推导出整套 Material3 `ColorScheme`（浅色 / 深色各一套）的纯函数，外加种子色的选取与回退链。**这一票只做纯逻辑和单测，不接 UI。**

这是整件事的地基：后面所有接线都调它，算法错了后面全白搭。所以它必须能在没有 Android 运行时的情况下单测。

**Blocked by:** None — can start immediately

## 要做的

- [x] 新增 `ui/theme/ThemePalette.kt`，提供纯函数：
  - `deriveColorScheme(seed: Color, darkTheme: Boolean): ColorScheme`——按 Material3 角色明度档位生成整套色板
  - `ThemeSeed` 选取与回退链：封面 vibrant → 现有 `selectCoverSwatch` → 用户预设色 → 默认色板（`BrandPrimary`）
- [x] **对比度靠结构保证**：`on*` 角色由明度档位决定（`onPrimary` 永远取最亮或最暗档），不做逐对事后校验。
- [x] 扩展现有 `CoverPalette.kt` 的 `hslOf` / `hslColor`，不要另造一套色彩转换。
- [x] 色相保留：推导结果的 `primary` 色相必须与种子色一致（允许 ±2° 误差）。
- [x] 明度档位单调：`surface` 系列在浅色下由浅到深、深色下由深到浅，不得乱序。
- [x] **不引入任何新依赖**（见 ADR-0005：material3 1.4.0 没有公开的种子色 API）。

## 验收标准

- [x] 单测覆盖：
  - 给定红色 / 绿色 / 蓝色种子，`primary` 色相保留
  - 浅色与深色两套都生成完整色板（无 `Color.Unspecified`）
  - `onPrimary` / `onSurface` 与各自底色的相对亮度差达到结构档位（断言亮度区间，不引入对比度库）
  - 灰调种子（饱和度 0）不产生彩色 `primary`
  - 回退链：vibrant 为空 → 回退 dominant → 再为空 → 预设色 → 再为空 → 默认色板
- [x] `:app:testDebugUnitTest --tests '*ThemePaletteTest'` 全绿
- [x] 现有 `CoverPaletteTest` 不回归

## 实现提示

- 现有 `CoverPalette.kt` 已经有 `hslOf(rgb: Int): FloatArray` 和 `hslColor(h,s,l): Color`（`internal`），直接复用。
- 角色不必求全：先覆盖 UI 实际用到的——`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` / `secondary` / `onSecondary` / `secondaryContainer` / `tertiary` / `onTertiary` / `tertiaryContainer` / `background` / `onBackground` / `surface` / `onSurface` / `surfaceVariant` / `onSurfaceVariant` / `outline` / `outlineVariant` / `error` / `onError`。其余角色给默认值即可（`lightColorScheme` / `darkColorScheme` 都有默认值）。
- `error` 保持红色系，**不要**跟着种子色走——错误色变绿会误导用户。
- 参考现有 `coverGradient` 的档位思路（浅色顶 0.84 / 中 0.92 / 底 0.97），但色板需要更细的档位。

## Comments

- 与工单 01（设计 token 底座）的关系：那个管间距/圆角，这个管颜色，两者正交。
- 若日后觉得黄色系观感不佳，替换的是 `deriveColorScheme` 这一个函数，其余代码不动。
