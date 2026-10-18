# 01 — 设计 token 底座

**What to build:** 把 Auxio 的设计量纲抽成一套 Compose `Dimens`（间距 4/8/12/16/20/24 dp、圆角 16/20 dp、触摸目标 48/56/64 dp），并至少改造一个现有页面用上它，验证这套 token 真的能用、且视觉无回退。

这是后续所有 UI 票的地基——没有它，各页面会各自散落魔法数字，视觉一致性无从谈起。

**Blocked by:** None — can start immediately

**Status:** done（代码已在 c557706 提交，QQ 下载页已验证使用 token）

- [x] 新增一套设计 token 常量（间距、圆角、触摸目标），集中定义、可全局引用
- [x] 至少一个现有页面改用这些 token，且该页面视觉与改造前一致（不追求变好看，只求不变）
- [x] 明暗两套主题下取值都正确
- [x] 不引入 Auxio 的任何源码（设计规格数值可用，代码不可用，见 ADR-0004）
- [x] 保留 Pulsar 现有字体（Righteous + Poppins），不换 Inter
