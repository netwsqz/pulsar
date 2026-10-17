# 04 — 导航壳改造

**What to build:** 顶层导航从现在的玻璃岛 chips（发现/全部音乐/专辑/播放列表/QQ下载 + 右侧搜索/设置/退出）改成 Auxio 的 tab 语义——发现 / Songs / Albums / Artists / Genres / Playlists。玻璃岛**保留视觉样式**，继续当导航载体，只是行为与语义照 Auxio 的 TabLayout 调。

**Blocked by:** 01（设计 token 底座）

**Status:** ready-for-human（代码完成，真机肉眼验证待做）

- [x] 顶层入口变为：发现 / Songs / Albums / Artists / Genres / Playlists
- [x] 玻璃岛视觉保留（用户仍一眼认出 Pulsar）
- [x] 选中态清晰，切换动效/行为向 Auxio tab 靠拢
- [x] 搜索/设置/退出入口仍可到达（保留在右侧图标或另行安置）
- [x] 详情页（专辑/播放列表等）仍能正确返回
- [x] 不做 Auxio 的 tab 可配置/可拖拽（属操作逻辑，本次 out of scope）
- [ ] 真机肉眼验证

## Comments

- **Artists / Genres 暂为占位页**：两个 tab 已在顶层导航中，但页面本体归工单 06；点进去显示「艺术家页正在做」空态，不引入死链接。
- **QQ 下载入口保留在顶部 chip**（用户决定）：顶层 chip 顺序 = 发现 / 歌曲 / 专辑 / 艺术家 / 流派 / 播放列表 / QQ 下载；右侧仍是搜索 / 设置 / 退出三个图标。
- **切换动效**：顶层 tab 之间按序号左右整屏滑入滑出（序号增大从右进，与 Auxio ViewPager 一致）；进详情页仍从右侧推入，返回退回来源页。
- **文案**：「全部音乐」改为「歌曲」，对齐 Auxio 的 Songs。

