# 11 — 艺术家组详情迁移

**What to build:** 艺术家组详情改用和专辑/播放列表详情同源的骨架，只是内容区换成网格；加载骨架带两行标题和一行副标题的文字区，从顶部开始，加载完不跳；流派分支不再存在，点艺术家进入的行为保持不变。

**Blocked by:** 02（删除流派页）、10（MediaDetailScaffold + 专辑详情迁移）。

**Status:** ready-for-human（代码完成，待真机验收）

**规格参考：** `spec.md` §6

- [x] 艺术家组详情使用共享骨架的网格变体，页面结构与专辑/播放列表详情同源。（`MediaDetailScaffold(gridColumns = 2, gridBody = …)`，头部抽成 `MediaDetailHeader`，两变体共用）
- [x] 加载骨架从顶部开始，加载完成不跳。（真实头部永远画一次；骨架只画卡片，从顶部排）
- [x] 流派分支已删除；点艺术家进入的组详情行为不变。（工单 02 已删，`LibrarySection.GroupDetail` 不变）
- [x] 离线时只显示已下载专辑；没有已下载内容时给说明型空态。（网格用 `visible.albums`，空态按 `offlineNotice` 分支）
- [ ] 构建通过；真机验证。（`:app:testDebugUnitTest` + `:app:assembleDebug` 均成功；真机待验收）

## Comments

### 实现记录（2026-11-03，agent）

**改了什么**

- **页面骨架**：`LibraryGroupDetailContent` 从「自写标题 + Box 居中包网格」改成走 `MediaDetailScaffold`
  的**网格变体**。`MediaDetailScaffold` 新增三个参数：`gridColumns`（非 null 切到 `LazyVerticalGrid`）、
  `gridBody`（`LazyGridScope.() -> Unit`）、`showActions`（默认 true；艺术家组传 false，不画播放/随机
  操作行——列的是专辑，没有整组播放语义）。头部抽成 `MediaDetailHeader`，列表变体与网格变体共用
  同一份，**真实头部永远只画一次**。
- **骨架不跳**：用户在实现前确认——骨架里**不再画假标题文字区**（与工单 10「真实头部只画一次」
  原则一致）；真实头部照常画，骨架只画专辑卡片。`AlbumGridSkeleton` 从嵌套的
  `LazyVerticalGrid` 改成 `Column + Row` 摆 2 列（放进详情网格 item 里，避免无界高度崩），
  内部间距与真实网格一致；顶部 Box 去掉居中，骨架从顶部开始。
- **摘要**（新纯函数 `ArtistGroupSummary.kt` + `ArtistGroupSummaryTest.kt`）：在线「N 张专辑」，
  离线「已下载 N 张 · 共 M 张」。离线分支复用 `downloadedTrackSummary`（给它加了 `unit` 参数，
  默认「首」），不另写一份。`serverTotal` 用过滤前的原始 `state.albums` 重新分组取数，避免
  离线时把过滤后的张数当成总数。
- **离线**：网格数据来自 `visible.albums`（已滤为「有下载的专辑」）；无可见专辑时空态文案
  「离线 · 这个艺术家还没有下载的专辑」；头部照常显示 `offlineNotice`。
- **顺手**：`AlbumGridSkeleton` 在专辑页的调用补了 `fillMaxWidth + 底部留白`，Box 去掉居中
  （修报告 #33 同类问题，并让骨架从顶部排）。两处数据层注释更新（流派页已删）。

**验证**

- `:app:testDebugUnitTest`：全部通过；新增 `ArtistGroupSummaryTest` 5 个用例。
- `:app:assembleDebug`：BUILD SUCCESSFUL（含 arm64-v8a NDK 层）。
- TDD：先 RED（`artistGroupSummary` 未定义编译失败）再补实现。

**审查记录**

- Standards：①`fullRow` 从 `internal` 改 `private`（只本文件用）；②`showActions=false` 时头部
  多出一个 16dp 空隙 → 把 `Spacer` 移进 `if (showActions)`；③`artistGroupSummary` 与
  `downloadedTrackSummary` 逻辑重复 → 给后者加 `unit` 参数复用；④`)                }` 格式错误已修。
  保留：`gridColumns`/`gridBody` 目前只有一个调用方，但头部抽取消除的是真实重复，分支本身很小。
- Spec：全部验收项达成。

**已知风险**

- 「离线共 M 张」的服务端总数依赖 `state.albums`；若断网时 `albums` 从未加载过（快照里只有曲目表），
  `serverTotal` 会是 null，摘要退化为「已下载 N 张 · 共 N 张」。与专辑/播放列表离线摘要同一限制。

**真机待验清单**

1. 在线进入艺术家：头部（大封面 + 组名 + 「艺术家」+ 「N 张专辑」）只出现一次；没有播放/随机按钮。
2. 加载中：头部是真的，下方是灰色卡片骨架，从顶部开始；加载完卡片不跳。
3. 点专辑卡进专辑详情，返回回到艺术家组。
4. 离线进入：只显示有下载的专辑；摘要「已下载 N 张 · 共 M 张」；顶部有「离线 · 只显示已下载」。
5. 离线进入无下载的艺术家：说明型空态「离线 · 这个艺术家还没有下载的专辑」。
