# 05 — 库页重写（Songs / Albums / Playlists）

**What to build:** 把「全部音乐 / 专辑 / 播放列表」三页的视觉按 Auxio 的列表/卡片语言重写——列表项结构（封面 + 主文本 + 副信息 + 更多按钮）、封面网格、间距圆角对齐 Auxio 量纲。删旧换新，不做新旧并存。

**Blocked by:** 01（设计 token 底座）、04（导航壳改造）

**Status:** ready-for-agent

- [ ] Songs 页按 Auxio 列表项样式（封面 + 歌名 + 副信息 + 更多按钮）
- [ ] Albums 页按 Auxio 封面网格/横滑样式
- [ ] Playlists 页按 Auxio 列表语言，与库页风格统一
- [ ] 保留现有功能：点击播放、加入队列、进入详情
- [ ] 加载中骨架、失败错误+重试、空态文案齐备
- [ ] 尺寸/圆角取自 01 的设计 token，不散落硬编码 dp
- [ ] 真机肉眼验证
