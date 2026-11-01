# 02 — 删除流派页

**What to build:** 顶层导航不再有「流派」。使用者只看得到发现、歌曲、专辑、艺术家、播放列表五个页签；点艺术家仍能正常进入该艺术家的专辑列表。流派相关的空态、文案、分组分支一并消失，`Album.genres` 数据字段保留。

**Blocked by:** 无 — 可立即开始。

**Status:** ready-for-human（代码完成）

**规格参考：** `spec.md` §12

- [x] 顶层导航恰为发现、歌曲、专辑、艺术家、播放列表；`navIndex` 按列表位置推导，测试按类型断言而不是硬编码索引。
- [ ] 界面上不再出现「流派」入口、空态或文案；点艺术家进入的组详情正常。
- [x] 分组只按艺术家；大小写不敏感、空白/UNKNOWN_ARTIST 不建组、稳定排序的既有边界用例通过。
- [x] `Album.genres`、DTO 与 `Fields=Genres` 保持不变。
- [x] 新增 ADR 0009 记录删除理由，并在 ADR-0008 的范围列表注明流派页已删除。
- [x] 构建与单测通过。

## Comments

### 实现记录（2026-11-01，agent）— commit `c8c4e34`

**改了什么**

- `LibrarySection.kt`：删除 `LibrarySection.Genres`；`TopLevelSections` 去掉「流派」，顶层 6 → 5；
  `GroupDetail` 去掉 `isGenre`，只表示艺术家组。
- `LibraryGroups.kt`：删除 `groupAlbumsByGenre`；`libraryGroups(albums)` 收敛为艺术家-only。
- `MainActivity.kt`：艺术家列表去掉 `byGenre` 参数与「暂无流派」空态；组详情去掉 `isGenre`；
  `LibrarySection.Artists` 分支收窄；组详情返回键固定回艺术家页；相关注释同步。
- `Album.kt`：只改注释（流派页删除后不再做客户端流派分组），字段保留。
- 测试：`LibrarySectionTest` 顶层断言改为 5 项按类型断言，`navIndex` 改为按类型断言相对顺序
  （不再遍历列表自证）；`LibraryGroupsTest` 删除三条流派用例，新增「`libraryGroups` 只按艺术家分组」。
- 文档：新增 `docs/adr/0009-remove-genres-page.md`；ADR-0008 范围列表注明流派页已删除；
  `CLAUDE.md` 导航 / `LibraryGroups` / ADR 索引同步。
- 数据层未动：`Album.genres`、DTO `Genres`、`Fields=Genres` 一行未改。

**验证**

- RED：先改 `LibrarySectionTest` 顶层断言跑出 `AssertionError`（期望 5 项、实际 6 项）。
- `:app:testDebugUnitTest`：**664 个用例，0 失败**（工单 01 后为 666，删 3 条流派用例、加 1 条艺术家用例，净 -2）。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**。
- 界面层 `grep 流派 / Genre / genre`：只剩数据层字段与 ADR / 测试里的有意说明，无 UI 入口、空态或文案。

**真机待验（验收第 2 项）**

1. 顶部玻璃岛只剩 5 个 chip：发现 / 歌曲 / 专辑 / 艺术家 / 播放列表，横滑顺序正确。
2. 点「艺术家」进入列表，再点某个艺术家，组详情正常显示该艺术家的专辑网格；返回键回到艺术家列表。
3. 深 / 浅色各看一次，chip 与列表无异常。

**顺带发现（未改）**

- `libraryGroups` 现在只是 `groupAlbumsByArtist` 的单行转发。ADR-0009 明确要求保留这个入口名，未删；
  若以后想简化，可把调用方直接改成 `groupAlbumsByArtist` 并删除别名。
- `LibraryGroupsContent(title = ...)` 只剩一个调用方且只传「艺术家」，`title` 参数可考虑内联；
  属下一轮清理，不在本单范围。
- 数据层注释 `JellyfinDtos.kt:45` / `JellyfinDataSource.kt:77` 仍写着「艺术家/流派页」。
  spec §12 明确豁免数据层 Genres 字段与注释，本次未改。
