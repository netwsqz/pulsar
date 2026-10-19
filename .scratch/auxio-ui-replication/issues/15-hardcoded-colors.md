Status: done（代码完成 + 单测全绿；观感需真机肉眼验收）

# 15 — 硬编码颜色收口

**What to build:** 把主题色板改成动态之后，那些绕开 `MaterialTheme` 直接写死的颜色会变成「不会跟着变」的孤儿。这一票把它们收进色板或明确保留。

**Blocked by:** 11

## 要做的

逐处处理下面这些**已知**的硬编码颜色（调查时确认的清单）：

- [x] `MainActivity.kt:543` 骨架微光：`Color.White` / `0xFF0F172A`
- [x] `MainActivity.kt:573` `cardBorderColor()`：`0x1AFFFFFF` / `0x14888888`
- [x] `MainActivity.kt:3951` 封面 scrim：`0x33000000` / `0x08000000`
- [x] `MainActivity.kt:1831`：`Color.White.copy(alpha = ...)`
- [x] `NowPlayingScreen.kt:127-129` `NpTextPrimary/Secondary/Faint`——**保留**（播放页文字恒白，见 ADR-0005），但在注释里写明为什么保留
- [x] `NowPlayingScreen.kt:546/659` 玻璃面——由工单 12 处理，本票不碰
- [x] 顺手清理 `ui/theme/Color.kt` 里**完全未使用**的死常量：`SuccessGreen`、`WarningAmber`

处理原则：

- **能换成色板角色就换**（scrim、描边、骨架微光）
- **换不了的明确保留并写注释说明**，不要为了「统一」把播放页的白色文字也改掉

## 验收标准

- [x] 切歌换色时，上述位置要么跟着变，要么有注释说明为什么不变
- [x] 没有新增硬编码色值
- [x] `Color.kt` 里不再有未使用的常量
- [x] 视觉与改造前在这些位置保持一致（本票不追求好看，只求不跳）
- [x] 现有单测全绿

## 实现提示

- 先 `grep` 一遍 `Color(0x` 和 `Color.White` / `Color.Black`，确认清单完整再动手。
- 骨架微光和 scrim 这类「叠加层」通常用 `onSurface` / `onBackground` 加透明度即可，不需要新角色。
- 本票与工单 12 有边界：**播放页的玻璃和文字归 12，其余页面归本票**。

## Comments

- 调查结论：真正吃主题色的 UI 只有 6 个文件、110 处，其中 52 处是 `onSurfaceVariant`；硬编码是少数但显眼的漏网之鱼。
- 收口结果（2026-10-18）：骨架微光 / 卡片描边 / 封面 scrim 已改走色板角色；`NpTextPrimary/Secondary/Faint` 保留白色并在 `NowPlayingScreen.kt:130-135` 写明理由；菜单玻璃高光的白色保留并在 `MainActivity.kt:1874-1877` 写明理由；`SuccessGreen` / `WarningAmber` 死常量已删除。
- **真机验收遗留**：`MainActivity.kt` 里 `Color.White.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.6f)` 这处是高光描边，刻意保留，需肉眼确认换色后仍像玻璃而不是彩色描边。

