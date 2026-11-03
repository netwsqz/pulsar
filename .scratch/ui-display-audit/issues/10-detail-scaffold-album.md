# 10 — MediaDetailScaffold + 专辑详情迁移

**What to build:** 专辑详情和播放列表详情共用一套页面骨架；真实头部永远只画一次，加载骨架不再重复画假头部；专辑曲目骨架行首是序号槽，不是假封面；离线摘要写「已下载 N 首 · 共 M 首」，N 和列表实际行数一致；离线时显示提示并隐藏下载按钮。

**Blocked by:** 07（歌曲与搜索列表行）、09（播放列表详情修复）。

**Status:** ready-for-human（代码完成，待真机验收）

**规格参考：** `spec.md` §6、§7

- [x] 专辑详情与播放列表详情共用同一套页面骨架；真实头部只画一次。（`MediaDetailScaffold`）
- [x] 专辑加载骨架不画假封面；曲目骨架行首是序号槽。（`TrackListSkeleton(leading = Number)`）
- [x] 离线摘要显示「已下载 N 首 · 共 M 首」，N 与列表实际行数一致，M 来自服务端总数。（`albumSummary` 与 `playlistSummary` 共用 `downloadedTrackSummary`，`AlbumSummaryTest` 覆盖）
- [x] 离线时显示离线视图提示，并隐藏下载按钮。（专辑改用派生的 `offlineNotice` 判断，与播放列表同口径）
- [x] 播放/随机/下载槽的位置和间距与播放列表详情一致。（同一骨架渲染）
- [ ] 构建通过；真机验证在线/离线/加载中。（构建通过：`:app:testDebugUnitTest` + `:app:assembleDebug` 均成功；真机待验收）
