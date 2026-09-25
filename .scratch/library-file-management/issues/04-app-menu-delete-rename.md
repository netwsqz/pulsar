# 04 — App：长按菜单 + 单曲删除 + 改名

**What to build:** 长按一首歌 → 菜单里多出「改名 / 删除」→ 弹确认框或输入框 → 调接口 → 界面立刻更新。

**Blocked by:** 03

**Status:** resolved

### 菜单

- [x] `MainActivity.kt:1299` 的 `AddToQueueMenu` 里，在现有「加入队列」之外加「改名」和「删除」
- [x] `TrackRow`（`MainActivity.kt:1158`）被专辑详情、播放列表详情、播放队列**共用**，改这一处四个页面同时生效——**不要逐页去改**
- [x] 菜单继续用现有的自绘 `Popup` 写法（项目里没有 `DropdownMenu`，别引新的）

### 删除

- [x] `AlertDialog` 二次确认，照 `QQDownloadScreen.kt:273` 那个「重下确认」的写法
- [x] **正在播的那首：菜单项直接置灰不可点。** 正在播时删文件，文件没了但播放器缓冲还在，是诡异 bug 的高发区
- [x] 删成功后：① 从当前列表移除 ② 若在播放队列里且**不是正在播的那首**，一并从队列移除（Q10 定的）
- [x] **不等重新拉接口**——本地立刻更新，后台再拉一次对齐
- [x] 失败走现有的 `friendlyError()`；`Unauthorized` 要单独提示"密钥不对，去设置页检查"

### 改名

- [x] `AlertDialog` + 三个 `TextField`：歌名 / 歌手 / 专辑，**预填当前值**
- [x] 至少一个字段非空才允许提交；提交中禁用按钮（照 `QQDownloadScreen.kt:527` 的防误关写法）
- [x] 成功后本地把这首歌的字段换掉，列表立刻显示新名字
- [x] 磁盘文件夹不会跟着变——**这是设计如此**，不要为此加提示或警告（见 spec「明确不做」）

### 测试

- [x] ViewModel 层覆盖：删除成功后列表与队列都被更新、删除失败时列表不动、改名成功后字段被替换、正在播的那首删除请求不会发出

## Comments

- **路径来源（本工单的前置问题）**：实测 Jellyfin 10.11.6 管理员账号下 `Fields=Path` 能返回每首歌的绝对路径，且 PC 服务的 `_checked()` 本就接受绝对路径——所以直接走「请求带 Fields=Path → DTO/Track 加 filePath → 原样传给删除/改名接口」，App 端不碰路径前缀。**注意 Path 仅管理员账号可见**，普通账号会拿不到（届时菜单仍可点，VM 会提示"拿不到文件路径"）。
- **与原计划的偏差**：「后台再拉一次对齐」**没有做**——刚删完就重拉 Jellyfin 列表，大概率把已删的歌又刷回界面（Jellyfin 的实时监控有延迟，spec「不催 Jellyfin 刷新」的已知风险同源）。本地更新就是本次会话的真值，重启 App 后以 Jellyfin 为准。改名同理只做本地替换。
- 删除/改名失败信息走独立的 `fileOpsMessage`（显示在弹窗内），不占用内容区的 `errorMessage`——不然一次删除失败会把整个列表页打成 ErrorState。
- 改名只提交**改动过的字段**（null = 不动），避免把预填的「Unknown artist」这类回退显示写进真标签。
- 回调经 `LocalTrackFileOps`（CompositionLocal）从 `MusicLibraryScreen` 发给所有 `TrackRow`，五个列表页零签名改动全部生效。
- 测试：`JellyfinViewModelTest` 新增 6 条（含"拿不到文件路径不发请求"），`JellyfinDataSourceTest` 新增 Fields=Path 断言，`JellyfinDtoTest` 新增 filePath 映射断言。全套 `:app:testDebugUnitTest` 绿。
