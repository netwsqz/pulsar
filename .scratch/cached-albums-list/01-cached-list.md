# 01 — 已缓存列表（按专辑）

**What to build:** 设置页缓存卡片加「已缓存」入口 → 独立子页面，按专辑分组看手机上已有的完整副本（封面 / 专辑名 / 歌手 / 已缓存 x/y 首）；点专辑行进详情页；每张专辑可删手机副本（正放的那首保留），「清空」按钮不动。判据 = 完整副本（复用 `computeCachedTrackIds`），半截副本不显示、不可删。

**Blocked by:** None（建立在手机本地缓存功能之上）

**Status:** ready-for-human（代码完成，真机验证项待做）

- [x] `CachedAlbums.kt`：纯函数 `groupCachedByAlbum`（按专辑名分组、进度统计、按歌手+专辑名排序；同名专辑并组与离线专辑详情页同一代价）
- [x] `JellyfinUiState.cachedAlbums` + `loadCachedAlbums()`（进页重拉；曲库没到位按空列表，不卡转圈）
- [x] `deleteAlbumCopies(album)`：只删那张专辑的键，正放的那首保留（同 `clearCache` 的 keepKeys 套路）；忙守卫和置位都留在发起线程（防快速连点竞态）；离线时标灰集合跟着刷新
- [x] 登录复位块 / 会话过期块清空 `cachedAlbums`（换账号不串数据）
- [x] 导航：`LibrarySection.CachedCopies`（isDetail）；设置页缓存卡片「已缓存」按钮；返回链 设置 ↔ 已缓存；详情页返回后消费掉 `detailOrigin`（修二级链回退死循环）
- [x] UI：`CachedCopiesContent` / `CachedAlbumRow`（封面 64dp + 进度 + 删除按钮 + 确认框，文案说明「歌还在电脑上」）
- [x] 测试：`CachedAlbumsTest` 4 个（分组/过滤/配对/空态）+ ViewModel 4 个（列表/删副本保护/删光消失/换账号清空）；全套 513 绿

## Comments

**实现记录（2026-09-27，agent）：**

- 专辑对象从专辑页拿（没加载过退快照）按专辑名配对——Track 没有专辑 ID，这是既定约束；配不上的不显示（开不了详情页）。
- 双轴 code review 结论：无硬伤；采纳 4 处修复（曲库没到位空列表不转圈、忙守卫竞态收口、`refreshCacheStats` 抽取、CONTEXT.md 术语措辞「正放的那一首」对齐共识）；跳过 4 处（返回链三臂重复——上提复位会破坏 CachedCopies 臂；按名匹配助手——两处形状不同；`albumCoverFor` 参数——与文件内其它 Content 的传参惯例一致；命名双家——都诚实）。
- 待真机验证：
  - 设置 → 已缓存：分组、封面、进度数字对得上（对照主界面专辑页的曲数）
  - 点行进详情页、返回回到已缓存、再返回回设置
  - 删一张专辑：确认框文案 → 列表即时少一行 → 设置页统计数下降；正放的那首还在播
  - 离线进这页照常显示；删掉的歌离线时变灰不可播
