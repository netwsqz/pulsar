Status: done（代码完成 + 单测全绿；观感需真机肉眼验收）

# 13 — 手动配色：预设色选择器

**What to build:** 设置页新增「外观」一节，16 个预设色块让用户自选兜底种子色；选择持久化，并显示「当前生效：封面 / 手动 / 默认」。

**Blocked by:** 11

## 要做的

- [x] 新增 `ThemePreferences`（DataStore，挂现有 `pulsarPreferencesDataStore`，与 `LoudnessPreferences` 同一套路）：
  - 存选中预设的 **ARGB `Int`**，不是序号
  - 没选过就不存（`null` = 未选择）
- [x] 新增 16 个预设色常量（Auxio 强调色集合，**去掉 Dynamic**）：Red / Pink / Purple / DeepPurple / Indigo / Blue / DeepBlue / Cyan / Teal / Green / DeepGreen / Lime / Yellow / Orange / Brown / Grey。**Indigo 用现有 `BrandPrimary`**，保证「没选过」时和默认色板一致。
- [x] 设置页新增「外观」一节（`SettingsContent`，`MainActivity.kt:2963`）：
  - 一张 `SurfaceCard`，16 个色块，可换行
  - 当前选中带勾
  - 一行说明：「仅在没有封面时生效」
  - 一行状态：「当前生效：封面 / 手动 / 默认」
- [x] 色块选中后立刻生效（重新推导色板），不需要重启。
- [x] 未选择任何预设时，状态显示「默认」。

## 验收标准

- [x] 设置页出现「外观」一节，16 个色块排布整齐、可点击
- [x] 选中一个色 → 重启 App 后仍然选中
- [x] 选中色块后：无封面 / 未播放时，界面用该色推导的色板；有封面色时仍以封面为准
- [x] 状态行准确反映当前生效来源（封面 / 手动 / 默认）
- [x] 深浅两种模式下色块与勾选都清晰可辨
- [x] `ThemePreferences` 有单测：默认 null、写入后读回、颜色值而非序号

## 实现提示

- 预设色**只是种子**，推导阶段会钳饱和度——所以预设本身是高饱和的，这是对的（见 ADR-0005）。
- 设置页现有卡片语言用 `SurfaceCard` + `SettingsSectionHeader`（`MainActivity.kt:3151`），照着写即可。
- 不要在「没封面色」时隐藏这一节——设置项不该忽隐忽现。
- 色块尺寸用 `Dimens` 里的常量，不要写魔法 dp。

## Comments

- Q5=B（兜底而非并列模式）、Q9=A、Q14=A、Q21=A、Q22=C 的落地。
