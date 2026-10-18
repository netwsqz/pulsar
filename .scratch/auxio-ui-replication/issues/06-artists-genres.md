# 06 — 艺术家页 + 流派页

**What to build:** 新增 Artists（艺术家）与 Genres（流派）两个库维度页，点进去能看到该艺术家/流派下的专辑或曲目。数据由**客户端分组**已有专辑数据而来——零新 API。

**Blocked by:** 01（设计 token 底座）、04（导航壳改造）

**Status:** done（代码完成，真机验收通过 2026-10-18）

- [x] 新增艺术家列表页，列出曲库中所有艺术家
- [x] 新增流派列表页，列出曲库中所有流派
- [x] 点艺术家/流派能进入其下专辑或曲目
- [x] 分组逻辑做成**独立纯函数**（仿 `DiscoverPicks`），无 IO、稳定排序
- [x] 纯函数单测覆盖边界：空列表、缺失 artist/genre 字段、同名归并
- [x] 视觉对齐 Auxio 列表语言，尺寸取自 01 的设计 token
- [x] 加载中骨架、空态文案齐备
- [x] 真机肉眼验证

## Comments

- **零新 API**：分组数据来自 `state.albums`，进 Artists / Genres 页时复用 `loadAlbums()`（幂等）。
- **流派字段**：`Album` 新增 `genres`（默认空列表，`JellyfinItemDto` 的 `Genres` 映射而来）。
  `getAlbums` 的 `Fields` 从 `ChildCount` 扩成 `ChildCount,Genres`——同一个请求多取一个字段，
  没有新增请求；`getRandomAlbums` / `getRecentlyAdded` 不取流派（它们不服务这两页）。
- **分组规则**（`LibraryGroups.kt`）：组名大小写不敏感归并、显示取首次出现的写法；空白值不产生分组；
  一张专辑可属于多个流派，同专辑内重复流派只算一次；组按名称、组内按专辑名稳定排序。
- **详情页**：新增 `LibrarySection.GroupDetail(name, isGenre)`，只存组名不存专辑列表——详情页
  按组名从当前专辑列表现算，列表刷新后自动跟着变；返回键退回来源页（Artists / Genres）。
- **封面**：服务端没有艺术家/流派图，列表行取组内第一张专辑的封面；无封面时走 `AlbumCover` 占位。
- **列表行**：复用搜索页的 `SearchResultRow`（封面 + 主副文本 + 分隔线），语言与库页一致。
- **专辑网格卡**：把 Albums 页的卡片抽成 `AlbumGridCard`（专辑页 / 组详情页共用），圆角改用
  `Dimens.CornerLarge`，与发现页横滑卡一致。
- **测试**：新增 `LibraryGroupsTest`（7 条纯函数用例）；`JellyfinDtoTest` 补 genres 映射；
  `JellyfinDataSourceTest` 的 `Fields` 断言更新；`LibrarySectionTest` 补 `GroupDetail` 哨兵值。
  UI 无 Compose 测试框架，按项目惯例真机肉眼验证。
