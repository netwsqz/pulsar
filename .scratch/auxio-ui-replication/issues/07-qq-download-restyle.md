# 07 — QQ 下载页重排

**What to build:** 把 QQ 下载页按 Auxio 的列表/卡片语言重排——搜索框、歌曲结果、专辑结果、下载任务列表统一到新视觉语言。**功能零回归**：凭证过期横幅、文件密钥卡、服务状态卡、选曲弹窗、重下确认全部保持行为不变。

**Blocked by:** 01（设计 token 底座）

**Status:** ready-for-human（代码完成，真机肉眼验证待做）

- [x] 搜索框、歌曲行、专辑行、任务行按 Auxio 列表/卡片语言重排
- [x] 未连接态的服务状态卡与 Auxio 风格对齐
- [x] 凭证过期横幅、文件密钥卡保持功能不变只换视觉
- [x] 选曲弹窗、重下确认弹窗行为不变
- [x] 下载/选曲/整专下载等操作全部不回归
- [x] 尺寸/圆角取自 01 的设计 token
- [ ] 真机肉眼验证

## Comments

- **列表语言**：歌曲/专辑/任务行统一成 `48dp 图标位 + 12dp + 主副文本 + 右侧按钮`，保留行间分隔线（与库页 `TrackRow` 同构）。QQ 结果没有封面图，图标位用 `MusicNote` / `Album` 占位；任务行状态图标（转圈/✓/×）也放进同一尺寸的图标位，三块列表左缘对齐。
- **卡片语言**：凭证过期横幅、服务状态、文件密钥这三个「设置块」套统一的 `QQCard`（`surfaceVariant` 45% + `outlineVariant` 细描边 + 16dp 圆角）。搜索框圆角改 16dp，两个 `AlertDialog` 圆角 20dp（`CornerExtraLarge`）。
- **token 边界**：间距/圆角/触摸目标全部走 `Dimens`；组件自身尺寸（spinner 16/20、图标 24、描边 1dp、弹窗列表限高 320dp）按工单 01 的约定保持字面量。
- **Review 修复**：① 去掉了新增的页内标题（玻璃岛 chip 已有「QQ 下载」，且列表内标题会跟着滚走）；② 恢复被误删的分隔线；③ 图标位与右间距合并进 `RowScope.QQIconSlot` / `QQLeadingIcon` / `QQStatusIcon`，三行不再各写一遍；④ 圆角从误用的 `SpaceMidMedium` 改为 `CornerLarge`；⑤ 凭证卡去掉重复的纵向内边距（外层 `QQCard` 已有）。
- **未改**：ViewModel、下载/选曲/整专下载/凭证重试/重下确认的全部回调与状态机一行未动；仅 `QQDownloadScreen.kt` 的布局与样式。
- **测试**：纯布局改动，无 Compose UI 测试设施（项目惯例真机肉眼验证）。`compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug` 均通过。
