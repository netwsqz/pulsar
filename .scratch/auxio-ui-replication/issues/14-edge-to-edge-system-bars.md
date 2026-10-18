Status: done（代码完成 + 单测全绿；需真机逐页验收）

# 14 — 系统栏透明化 + edge-to-edge

**What to build:** 系统状态栏与导航栏改为透明、内容延伸到栏下，图标明暗跟随主题色板的深浅模式。替换 `themes.xml` 里写死的两个颜色。

**Blocked by:** 11

## 要做的

- [ ] 开启 edge-to-edge（`enableEdgeToEdge()` 或等价配置），让内容绘制到系统栏下方。
- [ ] 移除 `res/values/themes.xml:6-7` 与 `res/values-night/themes.xml:6-7` 里写死的 `navigationBarColor` / `statusBarColor`（或改成透明）。
- [ ] 状态栏 / 导航栏图标明暗跟随 `darkTheme`，与色板一致。
- [ ] 核对所有页面在 edge-to-edge 下的内边距：
  - `statusBarsPadding()` / `navigationBarsPadding()` 已有的地方保持
  - **登录页**（`MainActivity.kt:624`）不在 `backgroundBrush` 内，需确认它也有正确的 insets
  - **全屏播放页**单独处理（工单 12 已接管图标明暗）
- [ ] 不改变任何页面的内容布局，只改系统栏处理。

## 验收标准

- [ ] 状态栏 / 导航栏透明，背景色直接透上去，不再是一条色带
- [ ] 浅色模式下图标深色、深色模式下图标浅色
- [ ] 所有页面顶部/底部内容不被系统栏遮挡（登录页、发现页、设置页、QQ 下载页、全屏播放页逐个看）
- [ ] Android 15+ 与更低版本设备行为一致（targetSdk 35 下旧 API 已废弃）
- [ ] 现有单测全绿

## 实现提示

- targetSdk 35 在 Android 15+ 上强制 edge-to-edge，`statusBarColor` / `navigationBarColor` 已废弃且不生效；低版本才需要显式设置。
- 项目已有 `statusBarsPadding()` / `navigationBarsPadding()` 的使用（如 `MainActivity.kt:950`、`SettingsContent` 的 `statusBarsPadding()`），说明布局本来就为这件事做了准备。
- 全屏播放页的图标明暗由工单 12 负责，本票不要重复处理。

## Comments

- Q23=A 的落地。
