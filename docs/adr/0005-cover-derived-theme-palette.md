# ADR-0005：主题色板由封面推导，并允许用户自选兜底色

**状态：** 已接受（2026-10-18）

## 背景

Pulsar 原本的主题是**两张写死的静态色板**（`ui/theme/Theme.kt` 里的 `LightColorScheme` / `DarkColorScheme`），品牌靛蓝贯穿全 App。封面取色只影响两处：一条高饱和背景渐变，和一个进度条强调色。

要让界面真正「跟着专辑走」，就得把封面取色从**局部装饰**升级成**主题色板的来源**——按钮、chip、卡片、滑块、文字色阶一起换。

这直接推翻了 `.scratch/auxio-ui-replication/spec.md` 里的一条 Out of Scope：

> 17 个强调色 + AMOLED 黑主题 + 动态取色——不引入，Pulsar 保留自有配色 + 封面取色。

字面上看是「保留封面取色」，但那句话写的时候，封面取色只指背景渐变。现在要改的是整件事的规模。

## 决定

**主题色板由「一个种子色」推导，种子色按固定优先级取：**

1. 当前播放曲目的封面**鲜艳色**（vibrant）
2. 用户自选的预设色（只在取不到封面色时生效）
3. 品牌靛蓝默认色板

**推导算法手写，不引入新依赖。** 在现有 `CoverPalette.kt` 的 HSL 工具上扩展：保留种子色相，按 Material3 的角色明度档位生成整套色板。

**主界面背景改由色板决定**（中性 tinted surface），**全屏播放页背景仍归封面**（封面渐变 + 模糊封面 + 黑 scrim，永远深色、文字恒白）。

## 理由

**为什么不直接用 Material3 的官方能力？** 查证过：material3 1.4.0 **不提供任何「任意种子色 → ColorScheme」的公开 API**。`TonalPalette` / `Hct` / `HctSolver` / `PaletteTokens` 全是 Kotlin `internal`；公开的 `dynamicLightColorScheme(context)` 只读系统壁纸色，且要求 API 31+（项目 minSdk 26）。网上流传的 `com.google.android.material:material-color-utilities` 坐标在 Google Maven 和 Maven Central **都不存在**。

**为什么不引第三方重打包？** `com.materialkolor:material-color-utilities` 新版要求 Gradle 9.8（项目是 8.10.2），`studio.lunabee.compose` 声明 JVM 21 变体（项目是 17），`com.google.android.material:material` 会把 appcompat + recyclerview 拖进纯 Compose 项目。三者都为了「更准的 HCT」付出构建或体积代价，而这次要的是「整 App 跟着专辑换色」，手写推导就能做到。**将来若黄色系观感不佳，替换的是同一个纯函数，其余代码不动。**

**为什么手动配色只做兜底？** 二选一（跟随封面 / 固定配色）会让用户在「固定配色」模式下完全看不到封面取色；而「手动打底、封面覆盖」会让用户选了色却大部分时候看不到效果。兜底模式规则一句话能说清，且不牺牲主要卖点。

**为什么主界面背景要让位给色板？** 高饱和封面渐变和 M3 的低彩度 surface 直接叠会打架。背景归色板、控件也归色板，才是一套自洽的系统。沉浸感由播放页承担——那一页的封面渐变 + 模糊 + scrim 是结构性成立的，不受影响。

## 后果

- **主界面那条高饱和封面渐变会消失**，取而代之的是带封面色调的中性 surface。这是本次最大的视觉变化。
- **`rememberCoverGradient` 的用途收窄**：不再喂主界面背景，只剩全屏播放页在用。
- **同一张封面在 App 里只允许有一个主色**：种子、强调色、播放页兜底渐变都取 vibrant，避免切页面时跳色。
- **对比度靠结构保证**：`on*` 角色由明度档位决定，不做逐对事后校验。
- **系统栏改透明 + edge-to-edge**：targetSdk 35 下 `statusBarColor` / `navigationBarColor` 已废弃，静态值在 Android 15+ 不生效。
- 设置页新增「外观」一节，16 个预设色（Auxio 强调色集合，去掉系统壁纸取色），存 ARGB 而非序号。

**被否掉的方案，以后别再提：**

- 引入 `material-color-utilities` 系依赖 —— 见上文三条代价，且当前没有公开可用的官方坐标
- 用系统动态取色（`dynamicLightColorScheme`）当主题来源 —— 那是壁纸色，不是专辑色，与本次目标无关
- 手动配色覆盖封面色 —— 用户会以为设置坏了
