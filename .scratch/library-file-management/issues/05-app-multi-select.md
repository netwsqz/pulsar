# 05 — App：批量多选删除

**What to build:** 从长按菜单进多选模式，勾一堆歌一次删掉。这是用户唯一要的"拓展"（Q6 只选了 a）。

**Blocked by:** 04

**Status:** resolved

- [x] 长按菜单里加一项「多选」，点了进入多选模式
- [x] 多选模式下：`TrackRow` 左侧出现勾选框，**点击整行 = 勾选，不再触发播放**
- [x] 顶部显示「已选 N 首」，配「全选 / 取消 / 删除」三个动作
- [x] 删除按钮在 0 首选中时置灰
- [x] 删除走**同一个确认框**（`04` 建的那个），确认后一次调接口批量删
- [x] 返回键先退出多选模式，而不是退出页面——接进 `MainActivity.kt:604` 的 `BackHandler`
- [x] 删除成功后：列表批量移除 + 队列里对应的歌一并移除（同 `04` 的规则），然后退出多选模式
- [x] 切到别的页面时自动退出多选模式（别让它跨页面残留）

### 测试

- [x] ViewModel 层覆盖：全选只选当前列表、批量删除成功后列表被正确裁剪、部分失败时成功的那些仍然生效且失败的给出提示

## Comments

## Comments

- 多选状态放 `JellyfinUiState.multiSelectIds`（空集合 = 非多选模式），VM 提供 enter/toggle/selectAll/clear 四个方法；「全选」只选 UI 传入的当前列表（按 section 取对应列表：全部音乐/发现=全部歌曲，专辑详情=albumTracks，播放列表详情=playlistTracks，搜索=结果歌曲）。
- TrackRow 经 `LocalMultiSelect`（CompositionLocal，与 LocalTrackFileOps 同点提供）感知多选：行首勾选框只做指示，整行点击 = 勾选；长按菜单新增「多选」项。
- 顶部操作条悬浮在玻璃岛正下方（与音量浮窗同位）：已选 N 首 + 全选/取消/删除；0 首选中时删除置灰；删除进**同一个确认框**（`deleteTarget` 改为 `List<Track>`，单曲=单元素列表）。
- 批量删除走一次 `POST /api/library/delete`（服务端逐个处理）；失败项用 `failed[].path`（原样回传请求路径）反推，成功的照常移除并退出多选，失败原因进提示。返回值 = 成功删除数，UI 据此清 Media3 队列 + Snackbar。
- 正在播的那首在 VM 层被过滤（不发请求），返回数不含它——批量里勾了正在播的歌会静默跳过（单曲入口才有置灰）。
- 返回键/侧滑先退出多选（后声明的 BackHandler 优先）；切换页面自动退出（`LaunchedEffect(section)`）。
- 测试新增 4 条：全选只选当前列表、批量删除裁剪+退出多选、部分失败成功项生效+提示、正在播的不发请求。全套绿。
